package com.aicompanion.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 {@link WebSearchTool#format} —— 把博查返回的 JSON 压成"给模型看的文本"那一步。
 *
 * <p>下面用的 JSON 是照着官方文档的响应格式手写的，不联网、不花钱。
 *
 * <p><b>为什么每条"失败路径"都要测：</b>工具方法是**模型在运行时决定要不要调**的，
 * 我们没法像普通接口那样在控制器里包一层 try/catch。所以"工具自己绝不抛异常"这条纪律
 * 只能靠测试守着——一旦它抛了，整个对话请求就会 500，用户只看到一个错误，什么都聊不了。
 */
class WebSearchToolTest {

    @Test
    @DisplayName("能解析出每条结果的标题、链接和摘要（有长摘要就用长的，没有就退回短的）")
    void formatsSearchResults() {
        String json = """
                {
                  "code": 200,
                  "log_id": "d71841ad20095f61",
                  "msg": null,
                  "data": {
                    "_type": "SearchResponse",
                    "webPages": {
                      "value": [
                        {"name": "杭州天气 - 中国天气网", "url": "https://weather.com.cn/hz",
                         "snippet": "杭州今天多云", "summary": "杭州今天多云，18~24 度，明天有小雨。"},
                        {"name": "杭州天气预报", "url": "https://example.com/2", "snippet": "未来三天以多云为主"},
                        {"name": "第三条", "url": "https://example.com/3", "summary": "第三条的长摘要"}
                      ]
                    }
                  }
                }
                """;

        String text = WebSearchTool.format(json);

        assertThat(text).contains("1. 杭州天气 - 中国天气网");
        assertThat(text).contains("https://weather.com.cn/hz");
        assertThat(text).contains("明天有小雨");     // 有 summary 时优先用 summary
        assertThat(text).contains("2. 杭州天气预报");
        assertThat(text).contains("未来三天以多云为主"); // 没有 summary 时退回 snippet
        assertThat(text).contains("3. 第三条");
    }

    @Test
    @DisplayName("一条都没搜到：返回一句人话，不抛异常")
    void handlesEmptyResults() {
        String json = """
                {"code": 200, "data": {"webPages": {"value": []}}}
                """;

        assertThat(WebSearchTool.format(json)).isEqualTo("没有搜到相关结果。");
    }

    @Test
    @DisplayName("响应根本不是合法 JSON（网关返回了 HTML 错误页 / 空响应）：返回一句人话，不抛异常")
    void handlesBrokenJson() {
        assertThat(WebSearchTool.format("<html>502 Bad Gateway</html>")).contains("解析失败");
        assertThat(WebSearchTool.format("")).contains("解析失败");
    }

    @Test
    @DisplayName("接口返回非 200 的错误码：把接口给的错误信息带出来，不抛异常")
    void handlesErrorCode() {
        String json = """
                {"code": 403, "msg": "invalid api key", "data": null}
                """;

        assertThat(WebSearchTool.format(json)).contains("invalid api key");
    }

}
