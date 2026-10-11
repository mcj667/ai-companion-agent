package com.aicompanion.agent.controller;

import java.util.List;

import com.aicompanion.agent.LoveReport;
import com.aicompanion.agent.app.LoveApp;
import com.aicompanion.agent.memory.ChatMode;
import com.aicompanion.agent.memory.ChatModeRegistry;
import com.aicompanion.agent.service.ChatHistoryService;
import com.aicompanion.agent.vo.MessageVO;
import com.aicompanion.agent.vo.SessionVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 之前 LoveApp 没有任何调用入口，没法验证对话记忆，所以补一个。
 */
@RestController
@RequestMapping("/love")
public class LoveController {

    private final LoveApp loveApp;

    /**
     * 历史记录相关的能力没塞进 LoveApp：那个类负责"跟模型说话"，
     * 查历史只是"把已经存下的数据读出来"，是另一件事，所以单独一个 Service。
     */
    private final ChatHistoryService chatHistoryService;

    /**
     * 记录「这一轮用的哪种模式」。
     *
     * <p>为什么记在控制器而不是 {@link LoveApp}：模式是 HTTP 层的概念——
     * 它完全由「调了哪个接口」决定。而 LoveApp 的职责是「跟模型说话」，
     * 这也是 {@link ChatHistoryService} 的注释里特意划的界，不该混进去。
     * 写在接口旁边，读代码时一眼就能对上。
     */
    private final ChatModeRegistry chatModeRegistry;

    public LoveController(LoveApp loveApp, ChatHistoryService chatHistoryService,
            ChatModeRegistry chatModeRegistry) {
        this.loveApp = loveApp;
        this.chatHistoryService = chatHistoryService;
        this.chatModeRegistry = chatModeRegistry;
    }

    /** 普通对话，一次性返回完整回复 */
    @PostMapping("/chat")
    public String chat(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        String reply = loveApp.doChat(message, chatId);
        // 拿到回复之后才记：失败的那一轮记忆里不会留下 AI 回复，这里也不该占位置
        chatModeRegistry.record(chatId, ChatMode.CHAT);
        return reply;
    }

    /**
     * 流式对话，SSE 逐字返回。
     *
     * <p>用 curl 看效果：{@code curl.exe -N -X POST ...}，-N 关掉缓冲才能看到逐字输出。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        return loveApp.doChatByStream(message, chatId)
                // 流正常放完才算这一轮成功，所以挂 doOnComplete 而不是 doFinally：
                // 中途被「停止」或报错都不该记，否则模式列表会多一条，后面全部错位。
                //
                // 这和 MessageChatMemoryAdvisor 写记忆的时机是严格一致的——它在流式场景下
                // 靠 MessageAggregator.aggregate() 落库，而那个类内部用的正是 Flux.doOnComplete。
                .doOnComplete(() -> chatModeRegistry.record(chatId, ChatMode.STREAM));
    }

    /**
     * 检索增强问答：回答时会带上知识库里的内容。
     *
     * <p>想确认检索生效，问一个只有知识库才知道的问题（比如文档里写死的具体建议），
     * 看它的回答是不是照着文档说的。
     */
    @PostMapping("/rag")
    public String rag(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        String reply = loveApp.doChatWithRag(message, chatId);
        chatModeRegistry.record(chatId, ChatMode.RAG);
        return reply;
    }

    /**
     * 联网搜索：模型自己决定要不要调搜索 / 抓取工具。
     *
     * <p>和上面几个接口最大的不同——回复长什么样不是这段代码决定的，
     * 是模型在运行时决定的：它可能直接回答（没调工具），也可能先搜、再抓某个页面、再回答。
     */
    @PostMapping("/search")
    public String search(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        String reply = loveApp.doChatWithSearch(message, chatId);
        chatModeRegistry.record(chatId, ChatMode.SEARCH);
        return reply;
    }

    /**
     * MCP 模式：工具不来自本地代码，而来自一个独立的 MCP 服务进程（这里配的是高德地图）。
     *
     * <p>代码长得跟 {@code /search} 几乎一样，但工具的来源完全不同：
     * 那边的工具是本项目里写的 {@code @Tool} 方法；这边的工具是应用<b>启动时</b>
     * 连上 MCP server、问它"你有哪些工具"换回来的，项目里根本没有这些方法的代码。
     *
     * <p>代价也在这里：MCP server 连不上时，不是"MCP 这个模式用不了"，
     * 而是<b>整个应用起不来</b>（详见 README 的 MCP 一节）。
     */
    @PostMapping("/mcp")
    public String mcp(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        String reply = loveApp.doChatWithMcp(message, chatId);
        chatModeRegistry.record(chatId, ChatMode.MCP);
        return reply;
    }

    /** 结构化输出，直接返回 LoveReport 对象（JSON） */
    @PostMapping("/report")
    public LoveReport report(@RequestParam String message, @RequestParam(defaultValue = "1001") String chatId) {
        LoveReport report = loveApp.doChatWithReport(message, chatId);
        chatModeRegistry.record(chatId, ChatMode.REPORT);
        return report;
    }

    /**
     * 会话列表（左侧那一栏）。
     *
     * <p>数据来自 Redis 的 ZSet {@code chat:sessions}，按最近聊过倒序。
     * 空会话不会出现——它要等第一次发言、被 {@code RedisChatMemory.add()} 记进名单后才会露头。
     */
    @GetMapping("/sessions")
    public List<SessionVO> sessions() {
        return chatHistoryService.listSessions();
    }

    /**
     * 某个会话的历史消息，用来还原页面上的气泡。
     *
     * <p>这里读的是和模型上下文同一份 Redis 数据（{@code chat:memory:{chatId}}），
     * 只是换了个出口给前端看。最多返回 maxMessages 条（默认 20）。
     */
    @GetMapping("/history")
    public List<MessageVO> history(@RequestParam String chatId) {
        return chatHistoryService.listMessages(chatId);
    }

    /** 删除会话：消息内容和会话名单一起清掉 */
    @DeleteMapping("/sessions")
    public void deleteSession(@RequestParam String chatId) {
        chatHistoryService.deleteSession(chatId);
    }
}
