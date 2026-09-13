package com.omniforge.ui.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * D8：协作阶段消息（会话记录落库/恢复）结构化内容。纯函数，无 FX 依赖。
 *
 * <p>content JSON：{@code {"kind":"statement|stage|checkpoint","alias":null,"round":0,"text":"..."}}。
 * statement = 讨论陈述（恢复渲染色点气泡）；stage = ②③④ 阶段全文；checkpoint = ⏸ 暂停事实。</p>
 */
public final class CollabStageMessage {

    public static final String KIND_STATEMENT = "statement";
    public static final String KIND_STAGE = "stage";
    public static final String KIND_CHECKPOINT = "checkpoint";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CollabStageMessage() {
    }

    /** 讨论陈述（alias/round 必填） */
    public static String statement(String alias, int round, String text) {
        return build(KIND_STATEMENT, alias, round, text);
    }

    /** 阶段全文（②结论/③执行/④验收） */
    public static String stage(String text) {
        return build(KIND_STAGE, null, 0, text);
    }

    /** ⏸ 暂停事实 */
    public static String checkpoint(String text) {
        return build(KIND_CHECKPOINT, null, 0, text);
    }

    /** 解析；非 JSON/kind 缺失返回 null（调用方回退平文本渲染） */
    public static Stage parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(content);
            if (root == null || !root.isObject() || root.path("kind").asText("").isBlank()) {
                return null;
            }
            return new Stage(root.path("kind").asText(),
                    root.path("alias").asText(null),
                    root.path("round").asInt(0),
                    root.path("text").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String build(String kind, String alias, int round, String text) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("kind", kind);
            if (alias != null) {
                node.put("alias", alias);
            }
            node.put("round", round);
            node.put("text", text == null ? "" : text);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // JSON 组装失败兜底：退化为平文本（恢复端按平文本渲染）
            return (text == null ? "" : text);
        }
    }

    /** 解析结果 */
    public record Stage(String kind, String alias, int round, String text) {
    }
}
