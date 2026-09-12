package com.omniforge.core.gateway;

import java.util.Map;

/**
 * 网关全局使用量快照（需求 4.2 成本熔断的数据来源）。
 *
 * @param byAlias                按模型别名的使用量
 * @param totalCalls             总调用次数
 * @param totalInputTokens       总输入 token
 * @param totalOutputTokens      总输出 token
 * @param totalEstimatedCostUsd  总估算成本（美元）
 */
public record GatewayUsage(Map<String, TokenUsage> byAlias, long totalCalls,
                           long totalInputTokens, long totalOutputTokens, double totalEstimatedCostUsd) {
}
