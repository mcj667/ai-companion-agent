package com.example.aiagent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * 维护「一共有哪些会话」这份名单。
 *
 * <p><b>为什么非要额外存一份名单：</b>
 * {@link RedisChatMemory} 的 key 是 {@code chat:memory:{chatId}}，不知道 chatId 就取不出东西。
 * 而 {@code ChatMemory} 接口只提供了 {@code add/get/clear} 三个方法，
 * <b>没有"列出所有会话"这一项</b>，所以光靠它做不出左侧列表。
 * 也不能用 {@code KEYS chat:memory:*} 去扫——KEYS 会阻塞整个 Redis，生产环境是禁用的。
 *
 * <p><b>用 ZSet 存：</b>
 * <pre>
 * key:    chat:sessions
 * member: chatId
 * score:  最后一次聊天的时间戳
 * </pre>
 * 用有序集合而不是普通 Set，是因为它自带排序：按 score 倒序取出来，
 * 天然就是「最近聊过的排最前面」，而且顺手带回了时间，前端要的 updatedAt 也有了。
 *
 * <p><b>为什么用 StringRedisTemplate：</b>
 * 项目里那个 {@code RedisTemplate<String, byte[]>} 是为 Kryo 二进制准备的，
 * 它的 value 序列化器是 {@code RedisSerializer.byteArray()}，
 * 往里面塞 String 会直接类型转换失败。会话名单存的是纯字符串，用 StringRedisTemplate 正合适
 * （这个是 Spring Boot 自动配置好、可以直接注入的）。
 */
@Component
public class

ChatSessionRegistry {

    /** 会话名单在 Redis 里的 key */
    private static final String SESSIONS_KEY = "chat:sessions";

    private final StringRedisTemplate redisTemplate;

    public ChatSessionRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记一笔：这个会话刚刚有新消息，把它挪到名单最前面。
     *
     * <p>ZADD 一个已存在的成员只会更新它的 score，不会重复添加，所以可以直接用。
     */
    public void touch(String chatId) {
        redisTemplate.opsForZSet().add(SESSIONS_KEY, chatId, System.currentTimeMillis());
    }

    /**
     * 按「最近聊过」倒序取出所有会话，带上最后活跃时间。
     *
     * <p>{@code reverseRangeWithScores(key, 0, -1)} 里的 {@code -1} 表示"取到最后一个"。
     */
    public List<SessionEntry> list() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(SESSIONS_KEY, 0, -1);

        List<SessionEntry> entries = new ArrayList<>();
        if (tuples == null) {
            return entries;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            entries.add(new SessionEntry(tuple.getValue(), tuple.getScore().longValue()));
        }
        return entries;
    }

    /** 把一个会话从名单里删掉（消息内容由调用方另外清）。 */
    public void remove(String chatId) {
        redisTemplate.opsForZSet().remove(SESSIONS_KEY, chatId);
    }

    /** 名单里的一项：会话 id + 最后活跃时间 */
    public record SessionEntry(String chatId, long lastActiveAt) {
    }
}
