package com.example.aiagent.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 记录「每一轮对话用的是哪种模式」，供刷新页面后还原气泡上的标签。
 *
 * <p><b>为什么非要另外存一份：</b>模式是 HTTP 层面的概念，它只体现在「调了哪个接口」
 * （{@code /love/rag} 还是 {@code /love/chat}）。而真正往 Redis 写记忆的是挂在
 * {@code ChatClient} 上的 {@code MessageChatMemoryAdvisor}，它只知道消息内容，
 * <b>不知道你调的是哪个接口</b>。所以这份信息在整条链路上压根没被采集过，
 * 只能在外面补一次。
 *
 * <p><b>Redis 结构：</b>
 * <pre>
 * key:   chat:modes:{chatId}
 * 类型:  List
 * 内容:  每轮一个模式值，按时间顺序，最早的在最左边
 * TTL:   和 chat:memory 一致（每次写入续期），两者同生共死
 * </pre>
 *
 * <p><b>为什么用 List 而不是像 {@link ChatSessionRegistry} 那样用 ZSet：</b>
 * 需求不一样。会话名单要按时间排序、还要去重（同一个会话反复出现只留一条），所以用 ZSet；
 * 模式列表是「按轮往后追加、允许重复、只关心顺序」——{@code RPUSH} 追加 + {@code LTRIM}
 * 只留最近若干轮，正好是两个命令解决的事。
 *
 * <p><b>为什么用 StringRedisTemplate：</b>同 {@link ChatSessionRegistry}，
 * 项目里那个 {@code RedisTemplate<String, byte[]>} 的 value 序列化器是
 * {@code RedisSerializer.byteArray()}，塞字符串会类型转换失败。
 *
 * <p><b>一个故意的取舍：</b>这份列表和消息列表<b>不严格同步</b>——消息按条数裁剪
 * （{@code RedisChatMemory.trim()} 只留最近 maxMessages 条），模式按轮数裁剪。
 * 两边对不齐是常态，靠 {@code ChatHistoryService} 从尾巴对齐去消化。
 * 这样做的代价是「最老的几轮可能标不出来」，而不是「标错」，见那边的注释。
 */
@Component
public class ChatModeRegistry {

    /** 每个会话一个模式列表 */
    private static final String KEY_PREFIX = "chat:modes:";

    private final StringRedisTemplate redisTemplate;

    private final ChatMemoryProperties properties;

    public ChatModeRegistry(StringRedisTemplate redisTemplate, ChatMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 记一轮。
     *
     * <p><b>调用时机很关键：只有这一轮真的拿到回复之后才能调。</b>
     * 失败或被用户中途停止的那一轮，记忆里不会留下 AI 回复，这里也不该占位置，
     * 否则后面每一轮的标签都会往前错一位。
     */
    public void record(String chatId, ChatMode mode) {
        String key = key(chatId);
        redisTemplate.opsForList().rightPush(key, mode.value());

        // 只留最近若干轮。这里直接复用 maxMessages 当轮数上限，不引入新的魔法数字：
        // maxMessages 条消息最多也就是 maxMessages/2 轮，留 maxMessages 轮已经有一倍余量。
        redisTemplate.opsForList().trim(key, -properties.maxMessages(), -1);

        // 跟 chat:memory 用同一个 TTL 并且每次续期，保证两份数据一起过期，
        // 不会出现「消息还在、模式先没了」的错位
        redisTemplate.expire(key, properties.ttl());
    }

    /** 按时间顺序返回这个会话用过的模式，最早的在前面；没记过就返回空列表 */
    public List<String> list(String chatId) {
        List<String> modes = redisTemplate.opsForList().range(key(chatId), 0, -1);
        return modes == null ? new ArrayList<>() : modes;
    }

    /** 删会话时清掉（消息内容由 {@code RedisChatMemory.clear()} 另外清） */
    public void clear(String chatId) {
        redisTemplate.delete(key(chatId));
    }

    private String key(String chatId) {
        return KEY_PREFIX + chatId;
    }
}
