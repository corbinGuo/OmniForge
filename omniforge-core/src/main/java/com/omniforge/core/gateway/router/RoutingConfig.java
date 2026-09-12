package com.omniforge.core.gateway.router;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层级路由配置（routing.yml，Batch2 替换双 alias 时代模型）。
 *
 * <p>层级 1~5 可自定义层名；每请求按身份键匹配规则（priority 升序），未命中走 defaultStrategy；
 * 复杂度评分（threshold/weights/keywords/exemplars 保留自旧双 alias 路由）用于在允许层级内择低/高档。</p>
 *
 * <p>{@code legacySimpleAlias/legacyAdvancedAlias} 仅兼容旧文件与"全库无 tier"的迁移兜底，
 * 新 GUI 不再写入。</p>
 */
public record RoutingConfig(Boolean enabled, Double threshold, Map<String, Double> weights,
                            List<String> keywords, List<String> exemplars,
                            Map<Integer, String> tierNames,
                            RoutingStrategy defaultStrategy,
                            List<RoutingRule> rules,
                            String legacySimpleAlias, String legacyAdvancedAlias) {

    private static final double DEFAULT_THRESHOLD = 0.6;

    public RoutingConfig {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        threshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
        weights = weights == null || weights.isEmpty() ? defaultWeights() : Map.copyOf(weights);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        exemplars = exemplars == null ? List.of() : List.copyOf(exemplars);
        tierNames = tierNames == null || tierNames.isEmpty()
                ? defaultTierNames() : Map.copyOf(tierNames);
        defaultStrategy = defaultStrategy == null ? RoutingStrategy.auto(null) : defaultStrategy;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static RoutingConfig defaults() {
        return new RoutingConfig(true, DEFAULT_THRESHOLD, defaultWeights(),
                List.of("分析", "对比", "评估", "设计", "架构", "代码", "算法", "数学",
                        "总结", "论文", "多步骤", "权衡", "优化", "排查"),
                List.of("请对比三种数据库方案并给出选型建议，分析各自在分布式场景下的优劣",
                        "请分析这段代码的时间复杂度，并设计优化方案"),
                defaultTierNames(),
                RoutingStrategy.auto(null),
                List.of(),
                "deepseek-chat", "gpt-4o-mini");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getThreshold() {
        return threshold;
    }

    public Map<String, Double> getWeights() {
        return weights;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public List<String> getExemplars() {
        return exemplars;
    }

    public Map<Integer, String> getTierNames() {
        return tierNames;
    }

    public RoutingStrategy getDefaultStrategy() {
        return defaultStrategy;
    }

    public List<RoutingRule> getRules() {
        return rules;
    }

    public String getLegacySimpleAlias() {
        return legacySimpleAlias;
    }

    public String getLegacyAdvancedAlias() {
        return legacyAdvancedAlias;
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("tokenLength", 0.4);
        map.put("keyword", 0.4);
        map.put("embedding", 0.2);
        return map;
    }

    public static Map<Integer, String> defaultTierNames() {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(1, "基础");
        map.put(2, "标准");
        map.put(3, "高级");
        map.put(4, "旗舰");
        map.put(5, "顶配");
        return map;
    }
}
