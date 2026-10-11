package com.example.aiagent.memory;

/**
 * 一轮对话用的是哪种模式。
 *
 * <p><b>为什么要单独搞一个枚举：</b>这个值有两个去处，必须处处一致——
 * <ul>
 *   <li>存进 Redis（{@link ChatModeRegistry}）</li>
 *   <li>通过 {@link com.example.aiagent.vo.MessageVO#mode()} 返回给前端</li>
 * </ul>
 * 而前端 {@code frontend/src/types/chat.ts} 里的 {@code ChatMode} 是个字符串联合类型，
 * 靠这些字符串去查中文标签。写错一个字母，标签就渲染成空白，而且不报错。
 * 所以让字符串只在 {@link #value()} 里出现一次，其余地方一律用枚举，改不动也就错不了。
 */
public enum ChatMode {

    /** 对应 POST /love/chat */
    CHAT("chat"),

    /** 对应 POST /love/chat/stream */
    STREAM("stream"),

    /** 对应 POST /love/rag */
    RAG("rag"),

    /** 对应 POST /love/report */
    REPORT("report"),

    /** 对应 POST /love/search：模型可以自己决定要不要调联网搜索 / 网页抓取工具 */
    SEARCH("search"),

    /**
     * 对应 POST /love/mcp。
     *
     * <p>和 {@link #SEARCH} 的区别不在"有没有工具"，而在工具<b>从哪来</b>：
     * search 的工具是我们自己写的 Java 方法，mcp 的工具来自一个独立的 MCP 服务进程。
     */
    MCP("mcp");

    private final String value;

    ChatMode(String value) {
        this.value = value;
    }

    /** 存 Redis 和返回前端时用的字符串，必须和前端 ChatMode 联合类型一致 */
    public String value() {
        return value;
    }
}
