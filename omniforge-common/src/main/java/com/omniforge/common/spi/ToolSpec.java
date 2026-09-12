package com.omniforge.common.spi;

import java.util.Map;

/**
 * 工具静态描述（元数据）。用于 OFT/MCP 规范转换、UI 展示与安全审批。
 *
 * @param name                 工具唯一名称（如 knowledge_search）
 * @param description          工具功能说明（供模型阅读，需自然语言化）
 * @param parametersSchema     参数 JSON Schema（Map 形式，可序列化为 JSON）
 * @param requiresConfirmation 是否需要在执行前人工确认（如 shell_executor 默认禁用）
 */
public record ToolSpec(String name, String description,
                       Map<String, Object> parametersSchema, boolean requiresConfirmation) {

    public ToolSpec {
        name = requireNotBlank(name, "name");
        description = requireNotBlank(description, "description");
        parametersSchema = parametersSchema == null ? Map.of() : Map.copyOf(parametersSchema);
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
