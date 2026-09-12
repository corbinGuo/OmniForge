package com.omniforge.common.spi;

import java.util.Map;

/**
 * 工具 SPI 契约（需求 4.5：MCP 标准 + OFT 自研规范双轨制）。
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>实现必须线程安全：同一实例可能被多个虚拟线程并发调用；</li>
 *   <li>执行不得阻塞调用方调度循环（重活自行转入后台执行或由调度层超时保护）；</li>
 *   <li>敏感数据（API Key、密码等）不得写入输出或日志（需求 9.2 安全底线）。</li>
 * </ul>
 */
public interface Tool {

    /** 工具元数据（静态、可缓存） */
    ToolSpec spec();

    /**
     * 执行工具调用。
     * <p>实现内部需自行兜底异常，返回 {@link ToolResult} 而非抛出；
     * 未预期的运行时异常会由引擎包装为 {@code ToolExecutionException} 并记录。</p>
     */
    ToolResult execute(ToolRequest request);

    /**
     * 本次调用是否需要执行前人工确认（HITL，TOOL_CONFIRMATION Q1）。
     * <p>默认回退到 {@link ToolSpec#requiresConfirmation()} 工具级标志；
     * 需要操作级区分的实现（如 file_read_write 仅 write/append/delete 需确认）
     * 覆写本方法按参数判定。</p>
     *
     * @param params 本次调用的实际参数（由调用方按 spec schema 组装）
     */
    default boolean requiresConfirmation(Map<String, Object> params) {
        return spec().requiresConfirmation();
    }
}
