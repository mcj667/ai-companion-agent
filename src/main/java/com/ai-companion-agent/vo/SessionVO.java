package com.example.aiagent.vo;

/**
 * 左侧会话列表里的一项。
 *
 * <p>字段对应前端 types/chat.ts 里的 Session。
 *
 * <p>有个小坑：Redis 里只存了"最后一次聊天的时间"，第一次聊天的时间没地方放，
 * 所以 createdAt 直接复用了 updatedAt。想精确的话得再存一个 Redis Hash 记创建时间。
 */
public record SessionVO(
        String id,          // chatId
        String title,       // 取第一句用户消息，截断
        long createdAt,
        long updatedAt
) {
}
