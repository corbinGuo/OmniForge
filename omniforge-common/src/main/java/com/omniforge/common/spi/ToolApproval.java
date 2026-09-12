package com.omniforge.common.spi;

import java.util.Map;

/**
 * 工具执行人工确认通道（HITL，TOOL_CONFIRMATION Q1-Q4）。
 *
 * <p>由装配层（如 GUI 模式的 app 模块）实现并注入 Agent 引擎：
 * 当请求身份为 {@code os:*}（桌面本地用户）且工具 {@link Tool#requiresConfirmation(Map)}
 * 判定需确认时，Agent 引擎在真正执行前调用 {@link #approve} 征求用户意见。</p>
 *
 * <p>约定：返回 {@code true} = 允许执行；{@code false} = 拒绝
 * （引擎侧统一反馈给模型 {@code ERROR: 用户拒绝执行 <工具名>}，循环继续但不执行）。
 * 超时视为拒绝（Q2-A，由实现方在对话框层处理）。未装配（Headless/企业无该 Bean）
 * 时引擎自动放行并记 WARN（Q3-A）。</p>
 */
@FunctionalInterface
public interface ToolApproval {

    /**
     * 征求用户确认。
     *
     * @param spec   待执行工具的描述（工具名等）
     * @param params 本次调用的实际参数（供确认框展示摘要）
     * @return true=允许执行；false=拒绝
     */
    boolean approve(ToolSpec spec, Map<String, Object> params);
}
