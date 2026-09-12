package com.omniforge.app.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OmniForgeMcpServerTest {

    private Tool echoTool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("echo", "回显", Map.of(), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("ok:echo:" + request.parameters(), 1);
            }
        };
    }

    private static byte[] frame(Map<String, Object> msg) throws Exception {
        ObjectMapper json = new ObjectMapper();
        byte[] body = json.writeValueAsBytes(msg);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);
        return frame;
    }

    @Test
    void stdio会话initialize工具清单与调用往返() throws Exception {
        Tool echo = echoTool();
        McpJsonMapper mapper = McpJsonMapper.createDefault();
        McpSchema.Tool mcpTool = McpToolCatalog.toMcpTool(echo, mapper);
        Map<String, Tool> byName = Map.of("echo", echo);

        ObjectMapper json = new ObjectMapper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 输入：initialize → tools/list → tools/call echo → ping，然后 EOF
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05", "clientInfo",
                        Map.of("name", "t", "version", "1")))));
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")));
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "echo", "arguments", Map.of("x", 1)))));
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 4, "method", "ping")));

        OmniForgeMcpServer.serve(new ByteArrayInputStream(in.toByteArray()), out,
                List.of(mcpTool), byName, json);

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("serverInfo").contains("protocolVersion").contains("2024-11-05");
        assertThat(text).contains("\"echo\"");                       // tools/list 含工具名
        assertThat(text).contains("ok:echo:");                       // tools/call 回显
        assertThat(text).doesNotContain("\"error\"");                // 无协议错误
        assertThat(text).contains("\"result\":{}");                  // ping 空结果
    }

    @Test
    void 未知工具返回isError而非协议错误() throws Exception {
        Tool echo = echoTool();
        McpJsonMapper mapper = McpJsonMapper.createDefault();
        McpSchema.Tool mcpTool = McpToolCatalog.toMcpTool(echo, mapper);
        Map<String, Tool> byName = Map.of("echo", echo);
        ObjectMapper json = new ObjectMapper();

        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05"))));
        in.write(frame(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", "nope"))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OmniForgeMcpServer.serve(new ByteArrayInputStream(in.toByteArray()), out,
                List.of(mcpTool), byName, json);
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("未知工具").contains("\"isError\":true");
    }
}
