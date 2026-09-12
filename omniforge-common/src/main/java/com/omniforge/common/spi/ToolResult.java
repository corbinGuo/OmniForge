package com.omniforge.common.spi;

import java.util.Objects;

/**
 * 工具执行结果。
 *
 * @param status     执行状态
 * @param output     成功时的输出文本（失败/超时为空串）
 * @param error      失败/超时原因（成功为空串）
 * @param durationMs 执行耗时（毫秒）
 */
public record ToolResult(ToolStatus status, String output, String error, long durationMs) {

    /** 工具执行状态 */
    public enum ToolStatus {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败 */
        FAILED,
        /** 执行超时（由调度层判定） */
        TIMEOUT
    }

    public ToolResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    /** 构造成功结果 */
    public static ToolResult success(String output, long durationMs) {
        return new ToolResult(ToolStatus.SUCCESS, output, "", durationMs);
    }

    /** 构造失败结果 */
    public static ToolResult failure(String error, long durationMs) {
        return new ToolResult(ToolStatus.FAILED, "", Objects.requireNonNull(error, "error"), durationMs);
    }

    /** 构造超时结果 */
    public static ToolResult timeout(String error, long durationMs) {
        return new ToolResult(ToolStatus.TIMEOUT, "", error == null ? "tool execution timed out" : error, durationMs);
    }
}
