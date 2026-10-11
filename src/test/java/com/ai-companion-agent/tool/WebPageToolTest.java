package com.example.aiagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 {@link WebPageTool#htmlToText} —— HTML 变纯文本那一步。
 *
 * <p>纯单元测试：直接喂 HTML 字符串，不启动 Spring、不发网络请求。
 *
 * <p><b>为什么值得单独测：</b>这一步的输出会**原封不动进模型的上下文**。
 * 最典型的翻车方式是忘了删 {@code script}——那样页面的 JS 源码会整段被当成"正文"喂给模型，
 * 既白烧 token 又干扰回答，而且在页面上完全看不出来（用户只看到回答变差了）。
 * 所以专门写一条"JS 不能出现"的回归测试看着它。
 */
class WebPageToolTest {

    @Test
    @DisplayName("正文能提出来，HTML 标签不会留在结果里")
    void extractsVisibleText() {
        String html = """
                <html><head><title>天气页</title></head>
                <body>
                  <h1>杭州天气</h1>
                  <p>今天多云，18 到 24 度。</p>
                  <p>明天有小雨。</p>
                </body></html>
                """;

        String text = WebPageTool.htmlToText(html);

        assertThat(text).contains("杭州天气");
        assertThat(text).contains("今天多云，18 到 24 度。");
        assertThat(text).contains("明天有小雨。");
        assertThat(text).doesNotContain("<p>");
        assertThat(text).doesNotContain("<h1>");
    }

    @Test
    @DisplayName("script / style 里的内容必须被丢掉，不能当正文喂给模型")
    void dropsScriptAndStyleContent() {
        String html = """
                <html><head>
                  <style>body { color: red; }</style>
                  <script>var secret = "不要把我喂给模型"; function boot() { return 1; }</script>
                </head>
                <body><p>真正的正文</p></body></html>
                """;

        String text = WebPageTool.htmlToText(html);

        assertThat(text).isEqualTo("真正的正文");
        assertThat(text).doesNotContain("secret");
        assertThat(text).doesNotContain("color: red");
        assertThat(text).doesNotContain("function boot");
    }

    @Test
    @DisplayName("超长正文被截断：不能把一个几万字的页面整页塞进上下文")
    void truncatesVeryLongPage() {
        String html = "<html><body><p>" + "啊".repeat(10_000) + "</p></body></html>";

        String text = WebPageTool.htmlToText(html);

        assertThat(text).startsWith("啊");
        assertThat(text).endsWith("…（已截断）");
        // 截断到 3000 字，远小于原文的 10000 字
        assertThat(text.length()).isLessThan(4_000);
    }

}
