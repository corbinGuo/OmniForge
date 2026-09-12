package com.omniforge.core.gateway;

/**
 * 单模型累计使用量。成本按 models.yml 定价估算（仅作熔断参考，非账单依据）。
 *
 * @param calls            调用次数
 * @param inputTokens      累计输入 token
 * @param outputTokens     累计输出 token
 * @param estimatedCostUsd 累计估算成本（美元）
 */
public record TokenUsage(long calls, long inputTokens, long outputTokens, double estimatedCostUsd) {

    /** 零值常量 */
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0.0);

    /** 累计总 token 数 */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
