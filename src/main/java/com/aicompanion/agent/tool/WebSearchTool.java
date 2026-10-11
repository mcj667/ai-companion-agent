package com.aicompanion.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 联网搜索工具：给模型一个"去网上查"的能力。
 *
 * <p><b>先想清楚工具调用到底发生了什么：</b>
 * 模型<b>不会执行任何代码</b>。它只是"说"一句「我要调 webSearch，参数是 {"query":"今天天气"}」，
 * 真正发 HTTP 请求的是下面这个 Java 方法，返回的文本再被塞回对话里，模型才接着写答案。
 * 所以一轮工具调用 = <b>至少两次模型调用</b>，这也是它比普通对话慢、贵的原因。
 *
 * <p><b>description 是写给模型看的说明书，不是写给人看的注释。</b>
 * 模型完全靠这段话判断"什么时候该调这个工具"，写含糊了模型就会该调不调、或者乱调。
 * 这是工具调用里最容易被追问、也最影响效果的一环。
 */
@Component
@Slf4j
public class WebSearchTool {

    /** application.yaml 里没填 key 时的占位值（跟 LoveAppVectorStoreConfig 里那套判断保持一致） */
    private static final String PLACEHOLDER = "key";

    /** 一次搜几条。搜太多会把上下文塞满，而且模型也用不上 */
    private static final int RESULT_COUNT = 5;

    /** 每条摘要最多留多少字 */
    private static final int SNIPPET_MAX_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient = RestClient.create();

    private final String apiKey;

    private final String endpoint;

    public WebSearchTool(
            @Value("${bocha.api-key:}") String apiKey,
            @Value("${bocha.endpoint:https://api.bochaai.com/v1/web-search}") String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    @Tool(description = """
            联网搜索。当用户问的是实时信息（新闻、天气、股价、价格、赛事比分、最近发生了什么），
            或者你完全没有把握、需要外部资料时，用这个工具去网上搜。
            参数 query 是搜索关键词，用自然语言描述想查什么。
            返回若干条网页结果的标题、链接和摘要；摘要不够详细时可以再用 fetchWebPage 抓正文。
            """)
    public String webSearch(@ToolParam(description = "搜索关键词，用自然语言描述想查什么") String query) {
        if (this.apiKey == null || this.apiKey.isBlank() || PLACEHOLDER.equals(this.apiKey)) {
            log.info("没有配置博查 api-key，联网搜索不可用");
            return "联网搜索没有配置 api-key，现在用不了。请直接告诉用户你没法联网查资料。";
        }

        log.info("联网搜索: query=\"{}\"", query);
        try {
            String body = this.restClient.post()
                    .uri(this.endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "query", query,
                            "count", RESULT_COUNT,
                            "summary", true,
                            "freshness", "noLimit"))
                    .retrieve()
                    .body(String.class);

            String result = format(body == null ? "" : body);
            log.info("联网搜索返回 {} 字", result.length());
            return result;
        }
        catch (RuntimeException e) {
            // 刻意不往外抛：工具抛异常会让整个对话请求 500，用户什么都看不到。
            // 返回一句"失败了"等于把失败也当成一条工具结果交给模型，模型能顺势跟用户解释。
            // 具体原因（超时 / 401 / 连不上）只写进日志，给排查用。
            log.warn("联网搜索失败: {}", e.toString());
            return "联网搜索失败了，这次没查到。请告诉用户联网查询没成功。";
        }
    }

    /**
     * 把博查返回的 JSON 压成模型好读的纯文本。
     *
     * <p>单独拆成静态方法是为了能用普通单元测试直接喂一段假 JSON 进来断言，
     * 不用启动 Spring、不用真的发请求（照 {@code ChatHistoryService.resolveModes} 的做法）。
     *
     * <p>响应形状（官方文档核实过）：{@code data.webPages.value[]}，
     * 每条有 {@code name} / {@code url} / {@code snippet} / {@code summary}（开了 summary 才有）。
     *
     * @return 永远返回一段可以直接给模型看的文本，<b>任何情况下都不抛异常</b>
     */
    static String format(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        }
        catch (Exception e) {
            log.warn("搜索响应不是合法 JSON: {}", e.toString());
            return "搜索结果解析失败，没能拿到内容。";
        }

        // readTree 对空字符串和纯空白**不抛异常**，而是返回一个"缺失节点"。
        // 不显式挡掉的话，空响应会被当成"一条都没搜到"，把故障说成正常结果。
        if (root == null || root.isMissingNode() || !root.isObject()) {
            log.warn("搜索响应不是合法 JSON 对象");
            return "搜索结果解析失败，没能拿到内容。";
        }

        JsonNode code = root.path("code");
        if (code.isNumber() && code.asInt() != 200) {
            return "搜索接口返回错误：" + root.path("msg").asText("未知错误");
        }

        JsonNode items = root.path("data").path("webPages").path("value");
        if (!items.isArray() || items.size() == 0) {
            return "没有搜到相关结果。";
        }

        List<String> lines = new ArrayList<>();
        for (JsonNode item : items) {
            String title = item.path("name").asText("");
            String url = item.path("url").asText("");
            if (title.isBlank() && url.isBlank()) {
                continue;
            }

            // summary 是长摘要（要开 summary:true），没有就退回短摘要 snippet
            String text = item.path("summary").asText("");
            if (text.isBlank()) {
                text = item.path("snippet").asText("");
            }

            lines.add((lines.size() + 1) + ". " + title + "\n   " + url + "\n   " + truncate(text));
        }

        return lines.isEmpty() ? "没有搜到相关结果。" : String.join("\n\n", lines);
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > SNIPPET_MAX_CHARS ? flat.substring(0, SNIPPET_MAX_CHARS) + "…" : flat;
    }

}
