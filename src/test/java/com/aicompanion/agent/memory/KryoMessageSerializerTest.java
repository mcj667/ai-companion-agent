package com.aicompanion.agent.memory;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯序列化往返测试，不依赖 Redis。
 *
 * <p>Spring AI 的 Message 没有无参构造、且内部混用 final 字段和各种集合实现，
 * Kryo 能不能正确还原只有实测才知道，所以这里覆盖得细一点。
 */
class KryoMessageSerializerTest {

    private final KryoMessageSerializer serializer = new KryoMessageSerializer();

    private List<Message> roundTrip(List<Message> messages) {
        return serializer.deserialize(serializer.serialize(messages));
    }

    @Test
    @DisplayName("UserMessage 往返，含中文")
    void roundTripsUserMessage() {
        UserMessage original = new UserMessage("我女朋友最近总是不回我消息，我该怎么办？");

        List<Message> restored = roundTrip(List.of(original));

        assertThat(restored).hasSize(1);
        Message message = restored.get(0);
        assertThat(message).isInstanceOf(UserMessage.class);
        assertThat(message.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(message.getText()).isEqualTo(original.getText());
        assertThat(message).isEqualTo(original);
    }

    @Test
    @DisplayName("SystemMessage 往返")
    void roundTripsSystemMessage() {
        SystemMessage original = new SystemMessage("你是一个恋爱咨询师，说话要温柔。");

        List<Message> restored = roundTrip(List.of(original));

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(restored.get(0).getText()).isEqualTo(original.getText());
        assertThat(restored.get(0)).isEqualTo(original);
    }

    @Test
    @DisplayName("AssistantMessage 带 metadata 往返")
    void roundTripsAssistantMessageWithMetadata() {
        AssistantMessage original = AssistantMessage.builder()
                .content("先别急着追问，给她一点空间。")
                .properties(new java.util.HashMap<>(java.util.Map.of("finishReason", "stop", "id", "chatcmpl-1")))
                .build();

        List<Message> restored = roundTrip(List.of(original));

        assertThat(restored).hasSize(1);
        Message message = restored.get(0);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        assertThat(message.getText()).isEqualTo(original.getText());
        assertThat(message.getMetadata()).containsEntry("finishReason", "stop");
        assertThat(message).isEqualTo(original);
    }

    @Test
    @DisplayName("AssistantMessage 带 toolCalls 往返")
    void roundTripsAssistantMessageWithToolCalls() {
        AssistantMessage original = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"北京\"}")))
                .build();

        List<Message> restored = roundTrip(List.of(original));

        assertThat(restored).hasSize(1);
        AssistantMessage message = (AssistantMessage) restored.get(0);
        assertThat(message.hasToolCalls()).isTrue();
        assertThat(message.getToolCalls()).hasSize(1);
        assertThat(message.getToolCalls().get(0))
                .isEqualTo(new AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"北京\"}"));
    }

    @Test
    @DisplayName("多轮混合对话往返，顺序不乱")
    void roundTripsMixedConversation() {
        List<Message> original = List.of(
                new SystemMessage("你是恋爱咨询师"),
                new UserMessage("她三天没回我了"),
                new AssistantMessage("先观察一下，别连环追问。"),
                new UserMessage("那我今天要发消息吗？"),
                new AssistantMessage("可以发一条轻松的，别提这件事。"));

        List<Message> restored = roundTrip(original);

        assertThat(restored).hasSize(original.size());
        assertThat(restored).containsExactlyElementsOf(original);
    }

    @Test
    @DisplayName("空列表往返")
    void roundTripsEmptyList() {
        assertThat(roundTrip(List.of())).isEmpty();
    }
}
