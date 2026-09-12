package com.omniforge.core.agent;

import com.omniforge.common.spi.ToolResult;

/**
 * 单次工具调用记录（对应数据模型 TOOL_CALL_LOG 表的运行期镜像）。
 *
 * @param toolName  工具名称
 * @param arguments 调用参数（原始 JSON）
 * @param result    观察结果文本（成功时为工具输出，失败/未知工具时为错误描述）
 * @param status    执行状态
 * @param durationMs 工具执行耗时（毫秒）
 */
public record ToolCallRecord(String toolName, String arguments, String result,
                             ToolResult.ToolStatus status, long durationMs) {
}
