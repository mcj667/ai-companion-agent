package com.aicompanion.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 专门钉住 {@link ChatHistoryService#resolveModes} —— 「每轮的模式」对上「每条消息」的算法。
 *
 * <p>纯单元测试，不需要 Spring、也不需要 Redis：造几条 Message 直接调静态方法就行。
 *
 * <p><b>为什么单独为它写测试：</b>这是整条链路里唯一一处「靠推算对齐」的地方。
 * 消息和模式是两份独立的数据、裁剪规则还不一样，一旦算错一位，
 * 页面上表现为「标签整体串了一格」——不报错、不崩溃，就是安静地标错，
 * 肉眼很难发现。所以把它单独拎出来当纯函数测。
 */
class ChatHistoryServiceTest {

    private static List<Message> conversation(String... texts) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            // 奇数下标当 AI 回复，偶数下标当用户提问，模拟一问一答
            messages.add(i % 2 == 0 ? new UserMessage(texts[i]) : new AssistantMessage(texts[i]));
        }
        return messages;
    }

    @Test
    @DisplayName("完全对齐：每轮各用各的模式，用户消息跟它后面那条回复算同一轮")
    void alignsWhenCountsMatch() {
        List<Message> messages = conversation("u1", "a1", "u2", "a2");

        String[] modes = ChatHistoryService.resolveModes(messages, List.of("rag", "chat"));

        assertThat(modes).containsExactly("rag", "rag", "chat", "chat");
    }

    @Test
    @DisplayName("消息被 trim 掉最老的几轮、模式还在：从尾巴对齐，剩下的几轮依然正确")
    void alignsFromTailWhenOlderMessagesTrimmed() {
        // 消息窗口只留下了第 3、4 轮，而模式列表里第 1~4 轮都还在
        List<Message> messages = conversation("u3", "a3", "u4", "a4");

        String[] modes = ChatHistoryService.resolveModes(messages, List.of("chat", "stream", "rag", "report"));

        assertThat(modes).containsExactly("rag", "rag", "report", "report");
    }

    @Test
    @DisplayName("尾部有孤儿用户消息（调用失败，只存下提问没存下回复）不影响前面的对齐")
    void ignoresOrphanUserMessageAtTail() {
        // U1 后面没有回复：MessageChatMemoryAdvisor.before() 会先写用户消息，
        // 之后模型调用失败了，于是留下这么一条孤儿
        List<Message> messages = conversation("u1", "a1", "u2");

        String[] modes = ChatHistoryService.resolveModes(messages, List.of("rag"));

        // 只有真正有回复的那一轮敢标；孤儿消息没有对应记录，老老实实是 null
        assertThat(modes).containsExactly("rag", "rag", null);
    }

    @Test
    @DisplayName("一轮里夹着工具调用：没有正文的那条 AI 消息不占模式名额，标签不会整体串位")
    void toolCallTurnConsumesOnlyOneMode() {
        // 第 2 轮是「联网搜索」模式下的一轮，记忆里会多出两条中间消息：
        //   ① 模型说"我要调 webSearch"——有 toolCalls，但没有正文
        //   ② 工具返回的搜索结果——消息类型是 TOOL
        // 这两条都不该消费模式，否则这一轮会吃掉两个模式，而模式列表每轮只记一个，
        // 从这一轮起后面所有标签全部往前串一格（跟当初把 mode 写死成 "chat" 是同一类 bug）。
        List<Message> messages = List.of(
                new UserMessage("u1"),
                new AssistantMessage("a1"),
                new UserMessage("u2"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "webSearch", "{\"query\":\"杭州天气\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1", "webSearch", "1. 杭州天气 - 中国天气网\n   https://weather.com.cn/hz")))
                        .build(),
                new AssistantMessage("a2"));

        String[] modes = ChatHistoryService.resolveModes(messages, List.of("rag", "search"));

        // 第 1 轮是 rag，第 2 轮是 search。中间那两条跟它们所在的这一轮同色，但反正不会显示出来
        assertThat(modes).containsExactly("rag", "rag", "search", "search", "search", "search");
    }

    @Test
    @DisplayName("模式比消息少：只有最新的那几轮敢标，往前一律不猜")
    void leavesOlderTurnsUnknownWhenModesAreMissing() {
        List<Message> messages = conversation("u1", "a1", "u2", "a2", "u3", "a3");

        // 只有最新一轮记下了模式
        String[] modes = ChatHistoryService.resolveModes(messages, List.of("rag"));

        assertThat(modes).containsExactly(null, null, null, null, "rag", "rag");
    }

    @Test
    @DisplayName("老会话压根没记过模式：全部为 null，前端一个标签都不显示")
    void returnsAllNullForLegacySession() {
        List<Message> messages = conversation("u1", "a1");

        String[] modes = ChatHistoryService.resolveModes(messages, List.of());

        assertThat(modes).containsExactly(null, null);
    }

    @Test
    @DisplayName("空会话返回空数组")
    void returnsEmptyForEmptyConversation() {
        assertThat(ChatHistoryService.resolveModes(List.of(), List.of("rag"))).isEmpty();
    }
}
