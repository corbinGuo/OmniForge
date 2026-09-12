package com.omniforge.tools.mcp;

import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.tools.mcp.testserver.FakeMcpServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdio 端到端：真实 McpClientManager 连接子进程假 MCP 服务器，
 * 验证 JSON-RPC 初始化 → tools/list → tools/call 全链路。
 */
class McpStdioEndToEndTest {

    private static void awaitUntil(BooleanSupplier condition, int timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("超时未满足条件");
    }

    @Test
    void stdio服务器端到端() throws Exception {
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
        // surefire fork 的 java.class.path 仅为引导 jar，真实测试类路径由 surefire.test.class.path 提供
        String testClasspath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        java.nio.file.Path trace = java.nio.file.Files.createTempFile("mcp-trace", ".log");
        McpServerConfig config = McpServerConfig.stdio("fake", javaExe, List.of(
                "-cp", testClasspath, FakeMcpServer.class.getName(), trace.toString()), true);
        McpSettingsHolder holder = new McpSettingsHolder(new McpSettings(List.of(config)));
        McpClientManager manager = new McpClientManager(holder);
        try {
            manager.start();
            awaitUntil(() -> manager.tools().size() == 1, 15_000);

            var tool = manager.tools().get(0);
            assertEquals("mcp_fake_echo", tool.spec().name(), "工具名带服务器前缀");
            assertEquals("echo input text", tool.spec().description());

            ToolResult result = tool.execute(ToolRequest.of(Map.of("text", "你好")));
            assertEquals(ToolResult.ToolStatus.SUCCESS, result.status());
            assertTrue(result.output().contains("echo:你好"), "实际输出：" + result.output());
        } catch (AssertionError e) {
            String traceText = java.nio.file.Files.exists(trace)
                    ? java.nio.file.Files.readString(trace) : "（无交互记录）";
            throw new AssertionError(e.getMessage() + System.lineSeparator() + "协议交互记录：" + traceText, e);
        } finally {
            manager.stop();
        }
    }
}
