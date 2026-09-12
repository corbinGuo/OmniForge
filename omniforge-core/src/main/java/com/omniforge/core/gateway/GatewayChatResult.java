package com.omniforge.core.gateway;

import java.time.LocalDateTime;

/**
 * 网关统一响应（屏蔽底层 Spring AI 类型，便于上层与插件解耦）。
 *
 * @param alias        实际使用的模型别名
 * @param providerName 提供方名称
 * @param modelId      提供方模型 ID
 * @param text         回复正文
 * @param inputTokens  输入 token 数（底层未返回时为 0）
 * @param outputTokens 输出 token 数（底层未返回时为 0）
 * @param finishReason 结束原因（底层未返回时为 "unknown"）
 * @param durationMs   调用耗时（毫秒）
 * @param createdAt    完成时间
 */
public record GatewayChatResult(String alias, String providerName, String modelId,
                                String text, int inputTokens, int outputTokens,
                                String finishReason, long durationMs, LocalDateTime createdAt) {
}
