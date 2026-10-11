package com.aicompanion.agent.memory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连真实 Redis 的集成测试，风格照抄 {@link RedisChatMemoryTest}：
 * Redis 没启动时自动跳过，每次用随机 chatId，前后各清一次，不碰你已有的对话数据。
 *
 * <p>测的是 {@link ChatModeRegistry} 真实落到 Redis 上那几个命令
 * （{@code RPUSH} / {@code LTRIM} / {@code EXPIRE}）的行为——
 * 光靠单元测试证明不了这些，得真连一次。
 */
@SpringBootTest
class ChatModeRegistryTest {

    private static final String CONVERSATION_ID = "test-modes-" + UUID.randomUUID();

    /**
     * 故意把 key 前缀写成字面量，而不是引用 {@code ChatModeRegistry} 里的常量。
     * key 名是对外可见的约定（README 的「Redis 数据结构」里也写了），
     * 哪天有人顺手把前缀改掉，这里就应该红给他看。
     */
    private static final String KEY = "chat:modes:" + CONVERSATION_ID;

    /** 用来区分「保留的是哪几条」：四个值循环着记，看头尾就能判断裁掉的是哪一段 */
    private static final ChatMode[] CYCLE = {
            ChatMode.RAG, ChatMode.CHAT, ChatMode.STREAM, ChatMode.REPORT,
    };

    @Autowired
    private ChatModeRegistry chatModeRegistry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ChatMemoryProperties properties;

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisReachable(), "Redis 未启动，跳过集成测试");
        chatModeRegistry.clear(CONVERSATION_ID);
    }

    @AfterEach
    void cleanup() {
        if (redisReachable()) {
            chatModeRegistry.clear(CONVERSATION_ID);
        }
    }

    @Test
    @DisplayName("记录后能按时间顺序读回，最早的在前面")
    void recordsInOrder() {
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.RAG);
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.CHAT);
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.STREAM);

        assertThat(chatModeRegistry.list(CONVERSATION_ID))
                .containsExactly("rag", "chat", "stream");
    }

    @Test
    @DisplayName("同一个模式可以连续记多次（List 不去重，这是和 ZSet 名单的关键区别）")
    void allowsDuplicates() {
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.RAG);
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.RAG);

        assertThat(chatModeRegistry.list(CONVERSATION_ID)).containsExactly("rag", "rag");
    }

    @Test
    @DisplayName("超过 maxMessages 轮时只保留最近的，砍掉的是最老的那几轮")
    void trimsToMaxMessages() {
        int max = properties.maxMessages();
        for (int i = 0; i < max + 5; i++) {
            chatModeRegistry.record(CONVERSATION_ID, CYCLE[i % CYCLE.length]);
        }

        List<String> modes = chatModeRegistry.list(CONVERSATION_ID);

        assertThat(modes).hasSize(max);
        // 一共记了 max+5 轮，最早那 5 轮被 LTRIM 掉了，
        // 所以剩下的第一条应该是下标 5 那个（也就是第 6 轮）的模式
        assertThat(modes.get(0)).isEqualTo(CYCLE[5 % CYCLE.length].value());
        assertThat(modes.get(max - 1)).isEqualTo(CYCLE[(max + 4) % CYCLE.length].value());
    }

    @Test
    @DisplayName("写入时设置了过期时间，和 chat:memory 用同一个 TTL")
    void setsTtlOnWrite() {
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.RAG);

        Long ttlSeconds = redisTemplate.getExpire(KEY, TimeUnit.SECONDS);

        assertThat(ttlSeconds).isNotNull().isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(properties.ttl().toSeconds());
    }

    @Test
    @DisplayName("clear 之后读不到内容")
    void clearRemovesModes() {
        chatModeRegistry.record(CONVERSATION_ID, ChatMode.RAG);
        assertThat(chatModeRegistry.list(CONVERSATION_ID)).isNotEmpty();

        chatModeRegistry.clear(CONVERSATION_ID);

        assertThat(chatModeRegistry.list(CONVERSATION_ID)).isEmpty();
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("没记过的会话读到空列表而不是 null")
    void missingConversationReturnsEmptyList() {
        assertThat(chatModeRegistry.list("definitely-not-exists-" + UUID.randomUUID())).isEmpty();
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
