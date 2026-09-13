package com.omniforge.ui.collab;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * D7 方向 A：@ 提及自然发起解析（纯函数，无 FX 依赖）。
 *
 * <p>规则（Q1/Q2-A）：文本中 @别名 与已加载模型匹配——≥2 个 → 协作（返回 participants）；
 * 恰 1 个 → 指定该模型单聊；0 个 → 普通对话。模板由关键词映射（辩论/评审/头脑风暴/收敛/默认圆桌），
 * 「N轮」→ 轮次（1~10 钳制）。topic = 去掉 @提及 段与模板关键词后的剩余文本（strip，可空）。</p>
 */
public final class CollabMentionParser {

    /** 解析结果；kind = NONE / SINGLE / COLLAB */
    public record MentionResult(String kind, List<String> aliases, String template,
                                int rounds, String topic, List<String> unknownAliases) {
        public boolean isCollab() {
            return "COLLAB".equals(kind);
        }

        public boolean isSingle() {
            return "SINGLE".equals(kind);
        }
    }

    private static final Set<String> TEMPLATES = Set.of(
            "正反辩论", "方案评审", "发散头脑风暴", "一致性收敛", "圆桌讨论");

    private CollabMentionParser() {
    }

    public static MentionResult parse(String text, List<String> loadedModels) {
        List<String> unknown = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        String working = text == null ? "" : text;
        StringBuilder remainder = new StringBuilder();
        for (String token : working.split("(?<=\\s)|(?=\\s)")) {
            if (token.startsWith("@") && token.length() > 1) {
                String name = token.substring(1);
                if (matchesModel(loadedModels, name)) {
                    matched.add(resolveModel(loadedModels, name));
                    continue;
                }
                unknown.add(name);
            }
            remainder.append(token);
        }
        String body = remainder.toString().strip();
        int rounds = detectRounds(body);
        String template = detectTemplate(body);
        body = stripTemplateKeywords(body).strip();

        if (matched.size() >= 2) {
            return new MentionResult("COLLAB", List.copyOf(matched), template, rounds, body, unknown);
        }
        if (matched.size() == 1) {
            return new MentionResult("SINGLE", List.copyOf(matched), template, rounds, body, unknown);
        }
        // 无有效 @：未知名也要提示（用户可能拼错）
        return new MentionResult("NONE", List.of(), template, rounds, body, unknown);
    }

    /** 模板关键词映射（含默认圆桌） */
    public static String detectTemplate(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("辩论")) {
            return "正反辩论";
        }
        if (lower.contains("评审")) {
            return "方案评审";
        }
        if (lower.contains("头脑风暴") || lower.contains("发散")) {
            return "发散头脑风暴";
        }
        if (lower.contains("收敛")) {
            return "一致性收敛";
        }
        return "圆桌讨论";
    }

    /** 「N轮」→ 轮次（1~10 钳制；未指定默认 1） */
    public static int detectRounds(String text) {
        if (text == null) {
            return 1;
        }
        var matcher = java.util.regex.Pattern.compile("(\\d{1,2})\\s*轮").matcher(text);
        return matcher.find() ? Math.max(1, Math.min(10, Integer.parseInt(matcher.group(1)))) : 1;
    }

    /** 从主题中移除已识别的模板关键词与「N轮」表述 */
    public static String stripTemplateKeywords(String text) {
        String result = text == null ? "" : text;
        for (String word : List.of("正反辩论", "方案评审", "发散头脑风暴", "头脑风暴", "圆桌讨论",
                "一致性收敛", "评审", "辩论", "收敛", "发散")) {
            result = result.replace(word, " ");
        }
        result = result.replaceAll("\\d{1,2}\\s*轮", " ");
        return result.replaceAll("\\s{2,}", " ");
    }

    private static boolean matchesModel(List<String> models, String name) {
        return models.stream().anyMatch(m -> m.equalsIgnoreCase(name));
    }

    private static String resolveModel(List<String> models, String name) {
        return models.stream().filter(m -> m.equalsIgnoreCase(name)).findFirst().orElse(name);
    }
}
