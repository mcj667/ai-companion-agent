package com.aicompanion.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连真实 Redis 的集成测试。
 *
 * <p>Redis 没启动时自动跳过，不会让构建失败。每次用随机 chatId，
 * 并在前后各清理一次，不碰你已有的对话数据。
 */
@SpringBootTest
class RedisChatMemoryTest {

    private static final String CONVERSATION_ID = "test-" + UUID.randomUUID();

    @Autowired
    private RedisChatMemory redisChatMemory;

    @Autowired
    private RedisTemplate<String, byte[]> redisTemplate;

    @Autowired
    private ChatMemoryProperties properties;

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisReachable(), "Redis 未启动，跳过集成测试");
        redisChatMemory.clear(CONVERSATION_ID);
    }

    @AfterEach
    void cleanup() {
        if (redisReachable()) {
            redisChatMemory.clear(CONVERSATION_ID);
        }
    }

    @Test
    @DisplayName("写入后能原样读回，中文不乱码")
    void savesAndReadsBack() {
        List<Message> messages = List.of(
                new SystemMessage("你是恋爱咨询师"),
                new UserMessage("她三天没回我消息了"),
                new AssistantMessage("先别连环追问，给她一点空间。"));

        redisChatMemory.add(CONVERSATION_ID, messages);

        assertThat(redisChatMemory.get(CONVERSATION_ID)).containsExactlyElementsOf(messages);
    }

    @Test
    @DisplayName("多轮对话是追加，顺序不乱")
    void appendsAcrossTurns() {
        redisChatMemory.add(CONVERSATION_ID, List.of(new UserMessage("第一句"), new AssistantMessage("第一次回复")));
        redisChatMemory.add(CONVERSATION_ID, List.of(new UserMessage("第二句"), new AssistantMessage("第二次回复")));

        List<Message> history = redisChatMemory.get(CONVERSATION_ID);

        assertThat(history).hasSize(4);
        assertThat(history.stream().map(Message::getText))
                .containsExactly("第一句", "第一次回复", "第二句", "第二次回复");
    }

    @Test
    @DisplayName("超过 maxMessages 时只保留最近的那批")
    void trimsToMaxMessages() {
        int max = properties.maxMessages();
        List<Message> overflow = new ArrayList<>();
        for (int i = 0; i < max + 5; i++) {
            overflow.add(new UserMessage("第 " + i + " 句"));
        }

        redisChatMemory.add(CONVERSATION_ID, overflow);

        List<Message> history = redisChatMemory.get(CONVERSATION_ID);
        assertThat(history).hasSize(max);
        // 保留的是最后 max 条，最早那 5 条应该被丢掉
        assertThat(history.get(0).getText()).isEqualTo("第 5 句");
        assertThat(history.get(max - 1).getText()).isEqualTo("第 " + (max + 4) + " 句");
    }

    @Test
    @DisplayName("写入时设置了过期时间")
    void setsTtlOnWrite() {
        redisChatMemory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));

        Long ttlSeconds = redisTemplate.getExpire(properties.key(CONVERSATION_ID), TimeUnit.SECONDS);

        assertThat(ttlSeconds).isNotNull().isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(properties.ttl().toSeconds());
    }

    @Test
    @DisplayName("clear 之后读不到内容")
    void clearRemovesHistory() {
        redisChatMemory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));
        assertThat(redisChatMemory.get(CONVERSATION_ID)).isNotEmpty();

        redisChatMemory.clear(CONVERSATION_ID);

        assertThat(redisChatMemory.get(CONVERSATION_ID)).isEmpty();
        assertThat(redisTemplate.hasKey(properties.key(CONVERSATION_ID))).isFalse();
    }

    @Test
    @DisplayName("不存在的会话读到空列表而不是 null")
    void missingConversationReturnsEmptyList() {
        assertThat(redisChatMemory.get("definitely-not-exists-" + UUID.randomUUID())).isEmpty();
    }

    private boolean redisReachable() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        }
        catch (Exception e) {
            return false;
        }
    }
}
