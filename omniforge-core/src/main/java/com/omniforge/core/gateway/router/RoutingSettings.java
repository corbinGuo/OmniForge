package com.omniforge.core.gateway.router;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能路由规则（routing.yml，Pro 功能）。
 *
 * <p>复杂度分 = 各信号打分加权求和（缺失信号自动跳过并重归一化权重），
 * 分 ≥ threshold → advancedAlias（复杂问题用高级模型），否则 simpleAlias（简单问题用便宜模型）。</p>
 *
 * <p>字段缺失/损坏时归一化为默认值，保证配置文件不完整也能用。</p>
 *
 * @param enabled      是否启用智能路由
 * @param simpleAlias  简单问题模型别名（须存在于 models.yml）
 * @param advancedAlias 复杂问题模型别名（须存在于 models.yml）
 * @param threshold    复杂度阈值（0~1）
 * @param weights      信号权重（tokenLength/keyword/embedding）
 * @param keywords     复杂问题关键词表
 * @param exemplars    复杂问题样本（供 Embedding 相似度信号对比）
 */
public record RoutingSettings(Boolean enabled, String simpleAlias, String advancedAlias,
                              Double threshold, Map<String, Double> weights,
                              List<String> keywords, List<String> exemplars) {

    public RoutingSettings {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        simpleAlias = simpleAlias == null ? "" : simpleAlias;
        advancedAlias = advancedAlias == null ? "" : advancedAlias;
        threshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
        weights = weights == null || weights.isEmpty() ? defaultWeights() : weights;
        keywords = keywords == null ? List.of() : keywords;
        exemplars = exemplars == null ? List.of() : exemplars;
    }

    private static final double DEFAULT_THRESHOLD = 0.6;

    /** 默认规则：DeepSeek（简单）→ GPT（复杂），按常见中英混输场景设定阈值与关键词 */
    public static RoutingSettings defaults() {
        return new RoutingSettings(true, "deepseek-chat", "gpt-4o-mini", 0.6, defaultWeights(),
                List.of("分析", "对比", "评估", "设计", "架构", "代码", "算法", "数学",
                        "总结", "论文", "多步骤", "权衡", "优化", "排查"),
                List.of("请对比三种数据库方案并给出选型建议，分析各自在分布式场景下的优劣",
                        "请分析这段代码的时间复杂度，并设计优化方案"));
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

    public String getSimpleAlias() {
        return simpleAlias;
    }

    public String getAdvancedAlias() {
        return advancedAlias;
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("tokenLength", 0.4);
        map.put("keyword", 0.4);
        map.put("embedding", 0.2);
        return map;
    }
}
