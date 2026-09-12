package com.omniforge.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP → OFT 工具适配器（需求 4.5 双轨制转换器）：
 * 把 MCP 服务器工具包装为 OmniForge 工具 SPI（OFT），
 * 模型侧转换复用既有 OmniForgeToolCallback（OFT → 各模型工具调用格式），零新代码。
 *
 * <p>命名规则（用户确认）：{@code mcp_<服务器名>_<工具名>}，
 * 避免与内置工具及多服务器同名冲突。</p>
 */
public final class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;
    private final String mcpToolName;
    private final ToolSpec spec;

    /**
     * @param serverName MCP 服务器名（工具名前缀）
     * @param client     所属服务器的同步客户端
     * @param mcpTool    MCP 工具元数据
     */
    public McpToolAdapter(String serverName, McpSyncClient client, McpSchema.Tool mcpTool) {
        this.client = client;
        this.mcpToolName = mcpTool.name();
        this.spec = new ToolSpec("mcp_" + serverName + "_" + mcpTool.name(),
                mcpTool.description() == null || mcpTool.description().isBlank()
                        ? "MCP 工具 " + mcpTool.name() : mcpTool.description(),
                schemaToMap(mcpTool.inputSchema()), false);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Map<String, Object> arguments = request.parameters() == null
                    ? Map.of() : request.parameters();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(mcpToolName, arguments));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            String text = concatContent(result);
            boolean isError = Boolean.TRUE.equals(result.isError());
            if (isError) {
                log.warn("MCP 工具 [{}] 返回错误：{}", spec.name(),
                        text.length() > 200 ? text.substring(0, 200) + "…" : text);
                return ToolResult.failure(text.isBlank() ? "MCP 工具返回错误" : text, elapsed);
            }
            return ToolResult.success(text, elapsed);
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.warn("MCP 工具 [{}] 调用失败：{}", spec.name(), e.getMessage());
            return ToolResult.failure("MCP 调用失败：" + e.getMessage(), elapsed);
        }
    }

    /** 结果内容拼接：文本顺序拼接；图片/资源以占位说明（不落盘） */
    private String concatContent(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        StringBuilder text = new StringBuilder();
        if (content != null) {
            for (McpSchema.Content item : content) {
                if (item instanceof McpSchema.TextContent textContent) {
                    text.append(textContent.text());
                } else {
                    text.append("[非文本内容：").append(item.type()).append("]");
                }
            }
        }
        // 结构化内容（2025-06-18 协议）：无文本时序列化为 JSON 返回
        if (text.isEmpty() && result.structuredContent() != null) {
            try {
                return JSON.writeValueAsString(result.structuredContent());
            } catch (Exception e) {
                log.debug("结构化内容序列化失败：{}", e.getMessage());
            }
        }
        return text.toString();
    }

    /** JSON Schema → Map（OFT parametersSchema 透传，供模型工具调用格式转换） */
    private static Map<String, Object> schemaToMap(McpSchema.JsonSchema schema) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schema == null) {
            return map;
        }
        if (schema.type() != null) {
            map.put("type", schema.type());
        }
        if (schema.properties() != null) {
            map.put("properties", schema.properties());
        }
        if (schema.required() != null) {
            map.put("required", schema.required());
        }
        if (schema.additionalProperties() != null) {
            map.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.defs() != null) {
            map.put("$defs", schema.defs());
        }
        return map;
    }
}
