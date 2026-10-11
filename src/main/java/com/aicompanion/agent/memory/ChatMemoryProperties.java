package com.aicompanion.agent.memory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对话记忆的存储参数，全部可在 application.yaml 里覆盖，省略则用这里的默认值。
 */
@ConfigurationProperties(prefix = "ai-agent.chat-memory")
public record ChatMemoryProperties(

        /** Redis key 前缀，完整 key 是 keyPrefix + conversationId */
        @DefaultValue("chat:memory:") String keyPrefix,

        /** 每个会话最多保留多少条消息，防止上下文无限增长把 token 打爆 */
        @DefaultValue("20") int maxMessages,

        /** 会话过期时间，每次写入都会续期（滑动过期） */
        @DefaultValue("7d") Duration ttl) {

    public String key(String conversationId) {
        return keyPrefix + conversationId;
    }
}
