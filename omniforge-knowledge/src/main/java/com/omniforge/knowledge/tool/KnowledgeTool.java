package com.omniforge.knowledge.tool;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.knowledge.KnowledgeService;

import java.util.List;
import java.util.Map;

/**
 * knowledge_search 内置工具（需求 4.4：在知识库中语义检索相关信息）。
 * 参数与需求 4.4 工具调用示例一致：query + topK（默认 5）。
 */
public final class KnowledgeTool implements Tool {

    public static final String NAME = "knowledge_search";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeTool.class);

    private final KnowledgeService knowledgeService;
    private final int defaultTopK;

    public KnowledgeTool(KnowledgeService knowledgeService, int defaultTopK) {
        this.knowledgeService = knowledgeService;
        this.defaultTopK = defaultTopK;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "在本地知识库中语义检索相关信息，返回最相关的文档片段。"
                        + "可按分类精确过滤（category 留空则检索整个知识库）。",
                Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索关键词"),
                                "category", Map.of("type", "string",
                                        "description", "文档分类（可选，精确匹配；留空检索整个知识库）"),
                                "topK", Map.of("type", "integer", "description", "返回条数，默认 " + defaultTopK)),
                        "required", List.of("query")),
                false);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Object queryValue = request.parameter("query");
        if (queryValue == null || queryValue.toString().isBlank()) {
            return ToolResult.failure("缺少参数 query", 0);
        }
        int topK = defaultTopK;
        Object topKValue = request.parameter("topK");
        if (topKValue instanceof Number number) {
            topK = Math.max(1, Math.min(20, number.intValue()));
        }
        // 分类过滤（可选）：空串/缺省 = 全库检索
        Object categoryValue = request.parameter("category");
        String category = categoryValue == null || categoryValue.toString().isBlank()
                ? null : categoryValue.toString();
        long start = System.nanoTime();
        try {
            List<KnowledgeService.SearchHit> hits = knowledgeService.search(
                    queryValue.toString(), topK, category);
            // P1 检索日志：命中数 + Top 命中明细（排查向量库未命中）
            if (hits.isEmpty()) {
                log.info("知识库检索：query={}，category={}，命中 0 条（库为空、分类无匹配或相似度全部低于阈值）",
                        queryValue, category == null ? "（全库）" : category);
                return ToolResult.success("（知识库中未找到相关内容）", elapsedMs(start));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeService.SearchHit hit = hits.get(i);
                sb.append(i + 1).append(". [").append(hit.fileName())
                        .append(" #").append(hit.chunkIndex())
                        .append("] 相似度 ").append(String.format("%.4f", hit.score()))
                        .append('\n').append(hit.content()).append('\n');
            }
            log.info("知识库检索：query={}，category={}，命中 {} 条，Top1 相似度 {}（{}）",
                    queryValue, category == null ? "（全库）" : category, hits.size(),
                    String.format("%.4f", hits.get(0).score()), hits.get(0).fileName());
            return ToolResult.success(sb.toString().trim(), elapsedMs(start));
        } catch (IllegalStateException e) {
            log.warn("知识库检索不可用：{}", e.getMessage());
            return ToolResult.failure(e.getMessage(), elapsedMs(start));
        } catch (Exception e) {
            log.warn("知识库检索失败：{}", e.getMessage());
            return ToolResult.failure("知识库检索失败: " + e.getMessage(), elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
