package com.omniforge.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.guard.UrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * web_search 内置工具（需求 4.5：Tavily / SearXNG API，防 SSRF 攻击）。
 *
 * <p>优先级：配置了 Tavily API Key 用 Tavily；否则用 SearXNG；两者皆无则返回配置指引。
 * 所有出站 URL 经 {@link UrlGuard} 校验，查询参数安全编码。</p>
 */
public final class WebSearchTool implements Tool {

    public static final String NAME = "web_search";

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int MAX_SNIPPET_CHARS = 500;

    private final ToolsProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebSearchTool(ToolsProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    WebSearchTool(ToolsProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "联网搜索（Tavily 或 SearXNG），返回相关网页的标题、链接与摘要。",
                Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索关键词"),
                                "max_results", Map.of("type", "integer", "description", "返回条数，默认 5，上限 10")),
                        "required", List.of("query")),
                false);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Object queryValue = request.parameter("query");
        if (queryValue == null || queryValue.toString().isBlank()) {
            return ToolResult.failure("缺少参数 query", 0);
        }
        String query = queryValue.toString();
        int maxResults = clampResults(request.parameter("max_results"));

        long start = System.nanoTime();
        try {
            List<SearchItem> items;
            if (properties.getTavilyApiKey() != null && !properties.getTavilyApiKey().isBlank()) {
                items = searchTavily(query, maxResults);
            } else if (properties.getSearxngBaseUrl() != null && !properties.getSearxngBaseUrl().isBlank()) {
                items = searchSearxng(query, maxResults);
            } else {
                return ToolResult.failure("未配置搜索引擎：请设置 TAVILY_API_KEY 环境变量，"
                        + "或配置 omniforge.tools.searxng-base-url", 0);
            }
            if (items.isEmpty()) {
                return ToolResult.success("（无搜索结果）", elapsedMs(start));
            }
            return ToolResult.success(format(items), elapsedMs(start));
        } catch (Exception e) {
            log.warn("web_search 失败：query={}", query, e);
            return ToolResult.failure("搜索失败: " + e.getMessage(), elapsedMs(start));
        }
    }

    private List<SearchItem> searchTavily(String query, int maxResults) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "api_key", properties.getTavilyApiKey(),
                "query", query,
                "max_results", maxResults));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(properties.getSearchTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode root = send(httpRequest);
        return parseItems(root.path("results"));
    }

    private List<SearchItem> searchSearxng(String query, int maxResults) throws Exception {
        URI base = UrlGuard.requireSafeHttpUrl(properties.getSearxngBaseUrl());
        String url = base + (base.toString().endsWith("/") ? "" : "/")
                + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json";
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getSearchTimeoutSeconds()))
                .GET()
                .build();
        JsonNode root = send(httpRequest);
        return parseItems(root.path("results"));
    }

    private JsonNode send(HttpRequest httpRequest) throws Exception {
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("搜索服务返回 HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private List<SearchItem> parseItems(JsonNode results) {
        List<SearchItem> items = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode node : results) {
                items.add(new SearchItem(
                        node.path("title").asText(""),
                        node.path("url").asText(""),
                        node.path("content").asText("")));
            }
        }
        return items;
    }

    private static String format(List<SearchItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            SearchItem item = items.get(i);
            sb.append(i + 1).append(". ").append(item.title().isBlank() ? "（无标题）" : item.title()).append('\n');
            sb.append("   ").append(item.url()).append('\n');
            String snippet = item.content();
            if (snippet.length() > MAX_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_SNIPPET_CHARS) + "...";
            }
            sb.append("   ").append(snippet).append('\n');
        }
        return sb.toString().trim();
    }

    private static int clampResults(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(10, number.intValue()));
        }
        return 5;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record SearchItem(String title, String url, String content) {
    }
}
