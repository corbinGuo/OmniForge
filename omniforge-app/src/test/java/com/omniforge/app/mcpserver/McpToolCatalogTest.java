package com.omniforge.app.mcpserver;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.agent.DefaultToolRegistry;
import com.omniforge.tools.ToolsSettings;
import com.omniforge.tools.ToolsSettingsHolder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    private Tool tool(String name, boolean confirm, Map<String, Object> schema) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "desc-" + name, schema, confirm);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("ok:" + name, 1);
            }
        };
    }

    private ToolsSettingsHolder holder(boolean pythonOn) {
        return new ToolsSettingsHolder(new ToolsSettings(false, pythonOn, List.of()));
    }

    @Test
    void 仅暴露非mcp免确认且开关启用的工具() {
        ToolRegistry registry = DefaultToolRegistry.merging(List.of());
        registry.register(tool("echo", false, Map.of()));
        registry.register(tool("mcp_inner_call", false, Map.of()));
        registry.register(tool("shell_executor", true, Map.of()));   // 需确认 → 排除
        registry.register(tool("python_interpreter", false, Map.of())); // 开关关 → 排除
        registry.register(tool("knowledge_search", false, Map.of()));

        List<Tool> on = McpToolCatalog.select(registry, holder(true));
        List<String> names = on.stream().map(t -> t.spec().name()).toList();
        // 内置经 ServiceLoader 自动入册：断言门控行为（含/不含），不断言全集
        assertThat(names).contains("echo", "knowledge_search", "python_interpreter");
        assertThat(names).doesNotContain("mcp_inner_call", "shell_executor");

        // python 开关关闭时再排除
        List<String> off = McpToolCatalog.select(registry, holder(false))
                .stream().map(t -> t.spec().name()).toList();
        assertThat(off).contains("echo", "knowledge_search");
        assertThat(off).doesNotContain("python_interpreter", "mcp_inner_call", "shell_executor");
    }

    @Test
    void mcp工具schema补齐object壳() {
        Tool bare = tool("bare", false, Map.of());
        McpSchema.Tool mcp = McpToolCatalog.toMcpTool(bare, McpJsonMapper.getDefault());
        assertThat(mcp.name()).isEqualTo("bare");
        assertThat(mcp.inputSchema().type()).isEqualTo("object");
        assertThat(mcp.inputSchema().properties()).isNotNull();
        assertThat(mcp.description()).isEqualTo("desc-bare");
    }

    @Test
    void 调用映射成功与失败() {
        Tool okTool = tool("ok", false, Map.of());
        McpSchema.CallToolResult ok = McpToolCatalog.invoke(okTool, Map.of());
        assertThat(ok.isError()).isFalse();
        assertThat(ok.content()).hasSize(1);
        assertThat(((McpSchema.TextContent) ok.content().get(0)).text()).isEqualTo("ok:ok");

        Tool boom = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("boom", "boom", Map.of(), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                throw new IllegalStateException("测试异常");
            }
        };
        McpSchema.CallToolResult err = McpToolCatalog.invoke(boom, Map.of());
        assertThat(err.isError()).isTrue();
        assertThat(((McpSchema.TextContent) err.content().get(0)).text()).contains("测试异常");
    }
}
