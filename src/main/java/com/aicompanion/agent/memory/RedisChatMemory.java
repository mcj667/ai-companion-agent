package com.aicompanion.agent.memory;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 把对话记忆持久化到 Redis 的 ChatMemory 实现。
 *
 * <p>Redis 结构：{@code chat:memory:{conversationId}} -> 一整个消息列表的 Kryo 二进制。
 */
@Component
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private final RedisTemplate<String, byte[]> redisTemplate;

    private final KryoMessageSerializer serializer;

    private final ChatMemoryProperties properties;

    /** 会话名单，供"列出所有会话"接口使用（ChatMemory 接口本身给不了这个能力） */
    private final ChatSessionRegistry sessionRegistry;

    public RedisChatMemory(RedisTemplate<String, byte[]> redisTemplate, KryoMessageSerializer serializer,
            ChatMemoryProperties properties, ChatSessionRegistry sessionRegistry) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
        this.properties = properties;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 追加聊天记录。
     *
     * <p>注意这里是「读-改-写」，同一个 chatId 并发请求时会互相覆盖丢消息。
     * 单用户顺序对话没问题；要严格保证得加 WATCH/MULTI 乐观锁。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> history = new ArrayList<>(get(conversationId));
        history.addAll(messages);

        redisTemplate.opsForValue()
                .set(properties.key(conversationId), serializer.serialize(trim(history)), properties.ttl());

        // 顺手把这个会话记进名单，供"会话列表"接口使用。
        // 放在 add() 里是因为：这是整个项目里唯一知道"某个会话刚刚有新消息"的地方，
        // 在这儿记一笔，就不用额外触发、也不会漏。
        sessionRegistry.touch(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        byte[] data = redisTemplate.opsForValue().get(properties.key(conversationId));
        if (data == null || data.length == 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(serializer.deserialize(data));
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(properties.key(conversationId));
    }

    /**
     * 只保留最近 maxMessages 条。不做裁剪的话历史会一直涨，
     * 几轮之后就把模型的上下文窗口和 token 预算打爆了。
     */
    private List<Message> trim(List<Message> history) {
        int max = properties.maxMessages();
        if (max <= 0 || history.size() <= max) {
            return history;
        }
        return new ArrayList<>(history.subList(history.size() - max, history.size()));
    }
}
