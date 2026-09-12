package com.omniforge.tools.mcp;

import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolAdapterTest {

    private static McpSchema.Tool mcpTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("text", Map.of("type", "string")), List.of("text"), null, null, null);
        return new McpSchema.Tool("echo", null, "回显输入", schema, null, null, null);
    }

    private static McpToolAdapter adapter(McpSyncClient client) {
        return new McpToolAdapter("fake", client, mcpTool());
    }

    @Test
    void 元数据转换带服务器前缀且透传参数Schema() {
        McpToolAdapter adapter = adapter(mock(McpSyncClient.class));
        assertEquals("mcp_fake_echo", adapter.spec().name());
        assertEquals("回显输入", adapter.spec().description());
        assertTrue(adapter.spec().parametersSchema().containsKey("properties"),
                "JSON Schema 应透传进 OFT parametersSchema");
        assertEquals("object", adapter.spec().parametersSchema().get("type"));
    }

    @Test
    void 执行成功拼接文本内容() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("你好"), new McpSchema.TextContent("，世界")), false));

        ToolResult result = adapter(client).execute(ToolRequest.of(Map.of("text", "hi")));

        assertEquals(ToolResult.ToolStatus.SUCCESS, result.status());
        assertEquals("你好，世界", result.output());
    }

    @Test
    void 非文本内容以占位说明() {
        McpSyncClient client = mock(McpSyncClient.class);
        // 图片/资源内容 → 占位说明（不落盘）
        McpSchema.Content imageLike = new McpSchema.ImageContent(null, "image/png", "base64data");
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(imageLike), false));

        ToolResult result = adapter(client).execute(ToolRequest.of(Map.of()));
        assertEquals("[非文本内容：image]", result.output());
    }

    @Test
    void isError转失败结果() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("参数非法")), true));

        ToolResult result = adapter(client).execute(ToolRequest.of(Map.of()));

        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
        assertEquals("参数非法", result.error());
    }

    @Test
    void 调用异常兜底为失败不抛出() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new IllegalStateException("连接断开"));

        ToolResult result = adapter(client).execute(ToolRequest.of(Map.of()));

        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
        assertTrue(result.error().contains("连接断开"));
    }
}
