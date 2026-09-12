package com.omniforge.core.debate;

import java.util.List;

/**
 * 辩论运行请求（需求 4.3）。
 *
 * @param sessionId      会话 ID（记录/打断标识）
 * @param topic          辩论主题（用户输入）
 * @param systemText     辩论背景/规则说明（可为 null）
 * @param modelAliases   参与辩论的模型别名列表（2~10 个，v5.2 硬约束；CE 上限 3 由会话层校验）
 * @param maxRounds      最大轮次
 * @param timeoutSeconds 整体时间熔断（秒）
 * @param maxCostUsd     成本熔断预算（null = 不启用）
 */
public record DebateRequest(String sessionId, String topic, String systemText,
                            List<String> modelAliases, int maxRounds,
                            long timeoutSeconds, Double maxCostUsd,
                            Double consensusThreshold, int stalemateRounds,
                            double stalemateNoveltyThreshold, String judgeAlias,
                            String discussionMode) {

    /** 协作模式：辩论 / 讨论 / 头脑风暴 */
    public static final String MODE_DEBATE = "debate";
    public static final String MODE_DISCUSSION = "discussion";
    public static final String MODE_BRAINSTORM = "brainstorm";

    /** 单场辩论模型数量上下限（v5.2：ParallelAgent 的 validate 强制 2~10） */
    public static final int MIN_MODELS = 2;
    public static final int MAX_MODELS = 10;

    public DebateRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        modelAliases = modelAliases == null ? List.of() : List.copyOf(modelAliases);
        if (modelAliases.size() < MIN_MODELS || modelAliases.size() > MAX_MODELS) {
            throw new IllegalArgumentException(
                    "参与辩论的模型数量须在 " + MIN_MODELS + "~" + MAX_MODELS + " 之间，当前：" + modelAliases.size());
        }
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        }
        if (stalemateRounds < 1) {
            throw new IllegalArgumentException("stalemateRounds must be >= 1");
        }
        if (stalemateNoveltyThreshold < 0 || stalemateNoveltyThreshold > 1) {
            throw new IllegalArgumentException("stalemateNoveltyThreshold must be in [0, 1]");
        }
        if (discussionMode != null && !List.of(MODE_DEBATE, MODE_DISCUSSION, MODE_BRAINSTORM)
                .contains(discussionMode)) {
            throw new IllegalArgumentException("discussionMode 必须是 debate/discussion/brainstorm");
        }
    }

    /**
     * 便捷构造：maxRounds=3、timeout=300s、不启用成本熔断；
     * 共识阈值 0.92（注入 ConsensusDetector 时生效）、连续 3 轮新颖度 <0.10 判定哑火；
     * judgeAlias=null（无裁判）、discussionMode=debate。
     */
    public static DebateRequest of(String sessionId, String topic, String systemText,
                                   List<String> modelAliases) {
        return new DebateRequest(sessionId, topic, systemText, modelAliases, 3, 300, null,
                0.92, 3, 0.10, null, MODE_DEBATE);
    }
}
