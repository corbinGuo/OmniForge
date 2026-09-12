package com.omniforge.app.mcpserver;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.agent.ToolSchema;
import com.omniforge.tools.ToolsSettingsHolder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端工具目录（P1-4 反向：把 OmniForge 内置工具暴露给外部 AI 客户端）。
 *
 * <p>暴露三条件（设计 §1#2 用户确认）：① 非 {@code mcp_} 前缀（内层聚合工具
 * 不 double-hop）；② {@code requiresConfirmation=false}（stdio 无 UI 弹确认，
 * 默认排除 shell_executor 等危险工具）；③ 受工具开关控制的工具（python）需已启用。
 * schema 统一经 {@link ToolSchema} 补 object 壳。</p>
 */
public final class McpToolCatalog {

    private McpToolCatalog() {
    }

    /** 选择可暴露的工具集（排序稳定：按注册表原有顺序） */
    public static List<Tool> select(ToolRegistry registry, ToolsSettingsHolder tools) {
        List<Tool> selected = new ArrayList<>();
        if (registry == null) {
            return selected;
        }
        for (Tool tool : registry.all()) {
            ToolSpec spec = tool.spec();
            if (spec.name().startsWith("mcp_")) {
                continue; // 内层聚合工具不暴露（外部可直连那些服务器）
            }
            if (spec.requiresConfirmation()) {
                continue; // stdio 无 UI 弹确认，危险工具默认不进 MCP
            }
            if (!enabledBySwitch(spec.name(), tools)) {
                continue; // 工具开关（python）未启用不暴露
            }
            selected.add(tool);
        }
        return selected;
    }

    /** 开关门控：python_interpreter 需开关启用；其余非确认工具恒可暴露 */
    private static boolean enabledBySwitch(String name, ToolsSettingsHolder tools) {
        if (!"python_interpreter".equals(name)) {
            return true;
        }
        return tools != null && tools.current().pythonEnabled();
    }

    /** Tool → MCP tool 定义（schema 补 object 壳，经 mapper 序列化为 JSON 交给 SDK 解析） */
    public static McpSchema.Tool toMcpTool(Tool tool, McpJsonMapper mapper) {
        ToolSpec spec = tool.spec();
        return McpSchema.Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(mapper, toSchemaJson(spec.parametersSchema(), mapper))
                .build();
    }

    private static String toSchemaJson(Map<String, Object> rawSchema, McpJsonMapper mapper) {
        try {
            return mapper.writeValueAsString(ToolSchema.objectSchema(rawSchema));
        } catch (java.io.IOException e) {
            // schema 序列化失败（理论上 objectSchema 恒可序列化）：降级为空 object 壳
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    /** 执行工具并映射为 MCP 结果（异常兜底为 isError，绝不抛穿协议层） */
    public static McpSchema.CallToolResult invoke(Tool tool, Map<String, Object> arguments) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            ToolResult result = tool.execute(new ToolRequest(args, null, Map.of()));
            boolean ok = result.status() == ToolResult.ToolStatus.SUCCESS;
            String text = ok ? result.output()
                    : (result.error() == null ? result.status().name() : result.error());
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(text == null ? "" : text)), !ok);
        } catch (Throwable e) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())), true);
        }
    }

    /** 名称 → 工具 索引 */
    public static Map<String, Tool> indexByName(List<Tool> tools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            byName.putIfAbsent(tool.spec().name(), tool);
        }
        return byName;
    }
}
