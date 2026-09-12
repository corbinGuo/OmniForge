package com.omniforge.tools.mcp;

import com.omniforge.core.agent.DefaultToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolRegistrarTest {

    private static McpToolAdapter echoAdapter() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        McpSchema.Tool tool = new McpSchema.Tool("echo", null, "回显", schema, null, null, null);
        McpSyncClient client = mock(McpSyncClient.class);
        return new McpToolAdapter("fake", client, tool);
    }

    @Test
    void 注册与移除同步() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of());
        McpClientManager manager = mock(McpClientManager.class);
        McpToolAdapter adapter = echoAdapter();
        when(manager.tools()).thenReturn(List.of(adapter));

        McpToolRegistrar registrar = new McpToolRegistrar(manager, registry);
        registrar.start();
        assertTrue(registry.find("mcp_fake_echo").isPresent(), "MCP 工具应注册进 ToolRegistry");

        // 服务器下线 → 工具移除
        when(manager.tools()).thenReturn(List.of());
        registrar.refresh(List.of());
        assertFalse(registry.find("mcp_fake_echo").isPresent(), "消失的 MCP 工具应被移除");
        registrar.stop();
    }

    @Test
    void 同名冲突时内置工具优先() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of());
        McpToolAdapter builtin = echoAdapter(); // 先注册的同名工具（模拟内置工具先到）
        registry.register(builtin);

        McpClientManager manager = mock(McpClientManager.class);
        when(manager.tools()).thenReturn(List.of(echoAdapter()));
        McpToolRegistrar registrar = new McpToolRegistrar(manager, registry);
        registrar.start();
        // 注册表中仍为先注册者（内置优先，MCP 让位）
        assertTrue(registry.find("mcp_fake_echo").get() == builtin);
        registrar.stop();
    }
}
