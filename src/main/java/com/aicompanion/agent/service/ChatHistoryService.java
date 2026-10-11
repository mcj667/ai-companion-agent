package com.aicompanion.agent.service;

import java.util.ArrayList;
import java.util.List;

import com.aicompanion.agent.memory.ChatModeRegistry;
import com.aicompanion.agent.memory.ChatSessionRegistry;
import com.aicompanion.agent.memory.RedisChatMemory;
import com.aicompanion.agent.vo.MessageVO;
import com.aicompanion.agent.vo.SessionVO;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 把存在 Redis 里的对话记忆，翻译成前端能直接用的形状。
 *
 * <p>注意这跟"对话能力"是两件事，所以单独抽了一个 Service：
 * {@link com.aicompanion.agent.app.LoveApp} 负责跟模型说话，
 * 这里只负责把已经存下来的数据读出来给页面看。
 *
 * <p><b>要点：</b>{@code ChatMemory} 是给模型用的"上下文"，不是给前端用的"历史记录接口"。
 * 数据只流向模型、不流向浏览器，所以想在前端显示气泡，必须另外开 REST 接口去读同一份数据。
 */
@Service
public class ChatHistoryService {

    /** 会话标题最多取几个字 */
    private static final int TITLE_MAX_LENGTH = 20;

    private final RedisChatMemory chatMemory;

    private final ChatSessionRegistry registry;

    /** 每一轮用的模式。消息本身不记这个，见 {@link ChatModeRegistry} 的注释 */
    private final ChatModeRegistry modeRegistry;

    public ChatHistoryService(RedisChatMemory chatMemory, ChatSessionRegistry registry,
            ChatModeRegistry modeRegistry) {
        this.chatMemory = chatMemory;
        this.registry = registry;
        this.modeRegistry = modeRegistry;
    }

    /** 左侧会话列表，最近聊过的排前面 */
    public List<SessionVO> listSessions() {
        List<SessionVO> list = new ArrayList<>();

        for (ChatSessionRegistry.SessionEntry entry : registry.list()) {
            String chatId = entry.chatId();
            List<Message> messages = chatMemory.get(chatId);

            // 消息本身有 7 天 TTL，但名单里的成员不会跟着过期。
            // 不筛掉的话，过期会话会一直挂在列表上，点进去却一片空白。
            // 顺手把它从名单里清掉，免得每次都要查一遍。
            if (messages.isEmpty()) {
                registry.remove(chatId);
                modeRegistry.clear(chatId);
                continue;
            }

            // createdAt 只能用 updatedAt 顶替，原因见 SessionVO 的注释
            list.add(new SessionVO(
                    chatId,
                    buildTitle(messages),
                    entry.lastActiveAt(),
                    entry.lastActiveAt()));
        }
        return list;
    }

    /**
     * 某个会话的全部消息。
     *
     * <p>注意拿不全：{@code RedisChatMemory.trim()} 只保留最近 maxMessages 条
     * （application.yaml 里配的 20），这是为了避免上下文无限增长把 token 打爆。
     */
    public List<MessageVO> listMessages(String chatId) {
        List<Message> messages = chatMemory.get(chatId);
        String[] modes = resolveModes(messages, modeRegistry.list(chatId));
        List<MessageVO> list = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.TOOL) {
                // 工具返回的那条（搜索结果、网页正文）不该变成一个聊天气泡：
                // 它动辄几千字，而且 role 会被下面的三元判成 "assistant"，看起来像 AI 说了这么一坨。
                // 顺带说明：消息类型里的 TOOL 不是 USER，所以它也不会占用一个模式名额（见 resolveModes）。
                continue;
            }
            if (!StringUtils.hasText(message.getText())) {
                // 模型说"我要调工具"的那条 assistant 消息只有 toolCalls、没有正文，跳过免得前端渲染崩掉
                continue;
            }

            String role = message.getMessageType() == MessageType.USER ? "user" : "assistant";
            list.add(new MessageVO(
                    chatId + "-" + i,   // 后端没存 id，用"会话id-下标"现造一个，给前端当列表 key 用
                    role,
                    message.getText(),
                    "done",             // 能存下来的历史消息都算已完成
                    modes[i]));         // 当时用的模式；老数据没记过，为 null
        }
        return list;
    }

    /**
     * 把「每一轮的模式」对到「每一条消息」上。
     *
     * <p><b>为什么要这么麻烦：</b>消息和模式是两份各自独立的数据，而且两边裁剪规则不同——
     * 消息按条数砍（{@code RedisChatMemory.trim()} 只留最近 20 条），模式按轮数砍。
     * 所以<b>不能按下标硬对</b>，窗口一满就会整体错位。
     *
     * <p><b>解法是从尾巴往前对：</b>从最后一条往前走，每遇到一条 AI 回复就消费掉一个模式；
     * 用户消息跟它后面那条回复算同一轮，用同一个模式。因为两边都是「砍掉最老的」，
     * 从后往前数一定是准的。万一中间真错了一轮，也只影响更老的那几条，新的永远是对的。
     *
     * <p><b>顺带解决的两件事：</b>
     * <ul>
     *   <li><b>失败的轮次</b>：{@code MessageChatMemoryAdvisor.before()} 会先往 Redis 写一条
     *       用户消息，所以调用失败或被停止时，记忆里会留下一条没有回复的「孤儿用户消息」。
     *       因为只拿 AI 回复去消费模式，孤儿用户消息不占位置，不会把后面全顶歪</li>
     *   <li><b>老数据</b>：模式列表是空的，全部返回 null，前端一个标签都不显示。
     *       诚实地承认「不知道」，而不是随便猜一个</li>
     * </ul>
     *
     * <p>包级可见（而不是 private）是为了能用普通单元测试直接钉住这段逻辑——
     * 它是整个流程里最容易悄悄错一位的地方。
     *
     * @return 和 messages 等长的数组，元素是对应那条消息的模式，不知道则为 null
     */
    static String[] resolveModes(List<Message> messages, List<String> modes) {
        String[] result = new String[messages.size()];

        int seen = 0;
        String current = null;      // null 表示不知道：改动之前的老数据没记过模式
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (consumesMode(messages.get(i))) {
                seen++;
                int index = modes.size() - seen;
                current = index >= 0 ? modes.get(index) : null;
            }
            result[i] = current;
        }
        return result;
    }

    /**
     * 这条消息算不算"一轮的结束"，该消费掉一个模式。
     *
     * <p>必须是<b>有正文的</b> assistant 消息。加了工具调用之后，一轮里会出现第二条 assistant
     * 消息——模型说"我要调 webSearch"的那条，它只有 toolCalls、<b>没有正文</b>。
     * 如果它也参与计数，一轮就会吃掉两个模式，而模式列表每轮只记一个，
     * <b>从那一轮起后面全部错位</b>（跟当初把 mode 写死成 "chat" 是同一类 bug）。
     *
     * <p>注意工具返回的那条消息不用在这里排除：它的类型是 {@code TOOL} 而不是 {@code ASSISTANT}。
     *
     * <p>用 {@code StringUtils.hasText}（非空白）而不是 {@code != null}：工具调用请求那条的正文
     * 到底是 {@code null} 还是 {@code ""}，取决于 Spring AI 内部有没有做 null→空串 的转换，
     * 这一点没有把握。用"非空白"判断对两种情况都成立，就不用去赌了。
     */
    private static boolean consumesMode(Message message) {
        return message.getMessageType() == MessageType.ASSISTANT && StringUtils.hasText(message.getText());
    }

    /** 删掉一个会话：消息内容、名单、模式记录一起清 */
    public void deleteSession(String chatId) {
        chatMemory.clear(chatId);
        registry.remove(chatId);
        modeRegistry.clear(chatId);
    }

    /** 标题取第一句用户消息，太长就截断 */
    private String buildTitle(List<Message> messages) {
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.USER && message.getText() != null) {
                String text = message.getText().trim();
                return text.length() > TITLE_MAX_LENGTH
                        ? text.substring(0, TITLE_MAX_LENGTH) + "…"
                        : text;
            }
        }
        return "新会话";
    }
}
