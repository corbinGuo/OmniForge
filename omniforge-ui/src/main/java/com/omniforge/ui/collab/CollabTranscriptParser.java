package com.omniforge.ui.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D7 方向 B：讨论记录解析（纯函数，无 FX 依赖）。
 *
 * <p>优先解析结构化 JSON（Q6-B，{@code {"rounds":[{"round","texts":{别名:全文}}],"meta":{...}}}），
 * 每轮全量平铺；旧 run（transcriptData 为 null/{}）回退解析旧 transcript 文本的
 * {@code ◆ 别名：} 段（单层）；均失败 → 回退单全文块。任何路径都不再截断。</p>
 */
public final class CollabTranscriptParser {

    /** 单条讨论陈述（别名 → 全文） */
    public record Statement(String alias, String text) {
    }

    /** 一个讨论块：轮次分隔（round ≤0 表示无轮次概念）+ 该块内全部陈述 */
    public record TranscriptBlock(int round, List<Statement> statements) {
    }

    /** 解析结果；blocks = 平铺序列，singleFallback = 三个来源都无结构时的全文兜底 */
    public record TranscriptResult(List<TranscriptBlock> blocks, String caption,
                                   String winner, String singleFallback) {
        public boolean isEmpty() {
            return blocks.isEmpty() && (singleFallback == null || singleFallback.isBlank());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CollabTranscriptParser() {
    }

    /** 主入口：dataJson = 服务端 transcript-data（可为 null/"{}"）；legacyText = 旧 transcript 文本 */
    public static TranscriptResult parse(String dataJson, String legacyText) {
        if (dataJson != null && !dataJson.isBlank() && !"{}".equals(dataJson.strip())) {
            TranscriptResult parsed = parseStructured(dataJson);
            if (parsed != null && !parsed.blocks().isEmpty()) {
                return parsed;
            }
        }
        TranscriptResult legacy = parseLegacy(legacyText);
        if (legacy != null && !legacy.blocks().isEmpty()) {
            return legacy;
        }
        String fallback = legacyText == null || legacyText.isBlank() ? null : legacyText;
        return new TranscriptResult(List.of(), null, null, fallback);
    }

    /** 结构化 JSON 解析：字段缺失/形态不符返回 null（调用方走回退链） */
    static TranscriptResult parseStructured(String dataJson) {
        try {
            JsonNode root = MAPPER.readTree(dataJson);
            JsonNode roundsNode = root == null ? null : root.path("rounds");
            if (root == null || !root.isObject() || !roundsNode.isArray() || roundsNode.isEmpty()) {
                return null;
            }
            List<TranscriptBlock> blocks = new ArrayList<>();
            for (JsonNode roundNode : roundsNode) {
                int round = roundNode.path("round").asInt(0);
                JsonNode texts = roundNode.path("texts");
                if (!texts.isObject()) {
                    continue;
                }
                List<Statement> statements = new ArrayList<>();
                texts.properties().forEach(entry ->
                        statements.add(new Statement(entry.getKey(),
                                entry.getValue().asText(""))));
                if (!statements.isEmpty()) {
                    blocks.add(new TranscriptBlock(round, List.copyOf(statements)));
                }
            }
            if (blocks.isEmpty()) {
                return null;
            }
            JsonNode meta = root.path("meta");
            return new TranscriptResult(List.copyOf(blocks),
                    meta.path("caption").asText(null), meta.path("winner").asText(null), null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 旧文本回退：按行解析 {@code ◆ 别名：内容} 段（单层，round=0）；无段则 null */
    static TranscriptResult parseLegacy(String legacyText) {
        if (legacyText == null || legacyText.isBlank()) {
            return null;
        }
        Map<String, StringBuilder> byAlias = new LinkedHashMap<>();
        String caption = null;
        String winner = null;
        for (String line : legacyText.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("模式：") && caption == null) {
                caption = stripped;
            } else if (stripped.startsWith("🏆 获胜方：")) {
                winner = stripped.substring("🏆 获胜方：".length()).strip();
            } else if (stripped.startsWith("◆ ") && stripped.contains("：")) {
                int sep = stripped.indexOf('：');
                String alias = stripped.substring(2, sep).strip();
                String body = stripped.substring(sep + 1).strip();
                byAlias.computeIfAbsent(alias, k -> new StringBuilder())
                        .append(body);
            }
        }
        if (byAlias.isEmpty()) {
            return null;
        }
        List<Statement> statements = byAlias.entrySet().stream()
                .map(e -> new Statement(e.getKey(), e.getValue().toString()))
                .toList();
        return new TranscriptResult(List.of(new TranscriptBlock(0, List.copyOf(statements))),
                caption, winner, null);
    }
}
