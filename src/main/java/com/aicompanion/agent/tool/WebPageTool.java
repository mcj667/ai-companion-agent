package com.aicompanion.agent.tool;

import java.net.URI;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 网页抓取工具：把某个 URL 的页面正文读出来，给模型细看。
 *
 * <p>跟 {@link WebSearchTool} 是一对：搜索只给你几条摘要，摘要不够详细时，
 * 模型可以自己决定"我再去把那条结果的正文抓下来看看"。这就是"多步工具调用"。
 */
@Component
@Slf4j
public class WebPageTool {

    /** 正文最多留多少字。不截断的话，一个页面上万字，token 预算直接被打爆 */
    private static final int MAX_CHARS = 3000;

    /** 不少网站会按 UA 拦爬虫，带一个常见的浏览器 UA 比默认的 Java 客户端成功率高 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final RestClient restClient = RestClient.create();

    @Tool(description = """
            抓取一个网页的正文。当搜索结果的摘要不够详细，或者用户直接给出了一个链接时用它。
            参数 url 必须是完整的 http 或 https 地址（例如 https://example.com/a.html）。
            返回这个页面的纯文本正文，过长会被截断。
            """)
    public String fetchWebPage(
            @ToolParam(description = "要抓取的网页完整地址，必须带 http:// 或 https://") String url) {

        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "这个地址不是完整的 http/https 链接，抓不了。";
        }

        log.info("抓取网页: {}", url);
        try {
            String html = this.restClient.get()
                    // 必须用 URI.create 而不是直接传字符串：RestClient.uri(String) 会把参数
                    // 当成 URI **模板**，URL 里一旦有花括号就会被当成占位符，直接报错。
                    .uri(URI.create(url))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .retrieve()
                    .body(String.class);

            String text = htmlToText(html == null ? "" : html);
            log.info("抓取网页完成 {} 字: {}", text.length(), url);
            return text.isBlank() ? "这个页面没有抓到正文内容。" : text;
        }
        catch (RuntimeException e) {
            // 同 WebSearchTool：失败也当成一条"结果"返回，不要把异常扔给上层
            log.warn("抓取网页失败: {} - {}", url, e.toString());
            return "抓取这个网页失败了，没能拿到内容。";
        }
    }

    /**
     * HTML → 纯文本。
     *
     * <p>三步，每一步都有理由：
     * <ol>
     *   <li><b>先删 {@code script} / {@code style} / {@code noscript} / {@code iframe}</b>——
     *       这是最关键的一步。{@code Jsoup.text()} 会把标签里的内容也当正文，
     *       不删的话页面的 JS 源码会整段整段灌进模型上下文，既烧 token 又干扰模型。</li>
     *   <li>{@code document.text()} 取全部可见文字，再把连续空白压成一个空格</li>
     *   <li>截断到 {@link #MAX_CHARS} 字</li>
     * </ol>
     *
     * <p>包级可见，方便用普通单元测试直接喂 HTML 字符串进来断言。
     */
    static String htmlToText(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, noscript, iframe").remove();

        String text = document.text().replaceAll("\\s+", " ").trim();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "…（已截断）" : text;
    }

}
