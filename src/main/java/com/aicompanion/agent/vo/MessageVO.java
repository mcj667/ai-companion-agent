package com.aicompanion.agent.vo;

/**
 * 返回给前端的一条聊天消息（一个气泡）。
 *
 * <p>为什么不用后端的 {@code Message} 直接返回：
 * <ul>
 *   <li>{@code Message} 没有 id，前端列表渲染需要一个唯一 key</li>
 *   <li>{@code status} 是纯前端的 UI 状态，Redis 里压根没存过</li>
 *   <li>直接序列化 {@code Message} 会把内部的元数据一起吐出去，又乱又浪费</li>
 * </ul>
 * 所以这里专门做一个"给前端看的形状"，字段照抄前端 types/chat.ts 里的 ChatMessage。
 */
public record MessageVO(
        String id,
        String role,      // user 或 assistant
        String content,   // 消息正文
        String status,    // 历史消息一律 done
        String mode       // 当时用的模式（chat/stream/rag/report）；为 null 表示不知道，前端不显示标签
) {
}
