package com.omniforge.tools.mcp.testserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * 进程内假 MCP 服务器（stdio 传输，换行分隔 JSON-RPC）：端到端测试用。
 *
 * <p>支持的请求：initialize（回显客户端请求的协议版本，标准协商行为）/
 * tools/list / tools/call（echo 工具，回显 text 参数）；通知（initialized 等）不响应。</p>
 *
 * <p>可选参数 args[0]：交互日志文件——每收到一行追加一行（诊断用）。</p>
 */
public class FakeMcpServer {

    public static void main(String[] args) throws Exception {
        Path traceFile = args.length > 0 ? Path.of(args[0]) : null;
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (traceFile != null) {
                Files.writeString(traceFile, "recv: " + line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String response = handle(mapper, line);
            if (response != null) {
                if (traceFile != null) {
                    Files.writeString(traceFile, "send: " + response + System.lineSeparator(),
                            StandardOpenOption.APPEND);
                }
                out.write(response);
                out.newLine();
                out.flush();
            }
        }
    }

    private static String handle(ObjectMapper mapper, String line) throws Exception {
        JsonNode node = mapper.readTree(line);
        String method = node.path("method").asText("");
        String id = node.has("id") ? node.get("id").toString() : null;
        if (id == null) {
            return null; // 通知（initialized 等）→ 不响应
        }
        return switch (method) {
            case "initialize" -> {
                // 标准协商：回显客户端请求的协议版本（服务器支持全部三个版本）
                String version = node.path("params").path("protocolVersion").asText("2025-06-18");
                yield result(mapper, id, Map.of(
                        "protocolVersion", version,
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "fake", "version", "1.0")));
            }
            case "tools/list" -> result(mapper, id, Map.of("tools", List.of(
                    Map.of("name", "echo",
                            "description", "echo input text",
                            "inputSchema", Map.of(
                                    "type", "object",
                                    "properties", Map.of("text", Map.of("type", "string")))))));
            case "tools/call" -> {
                String text = node.path("params").path("arguments").path("text").asText("(empty)");
                yield result(mapper, id, Map.of("content", List.of(
                        Map.of("type", "text", "text", "echo:" + text))));
            }
            default -> null;
        };
    }

    private static String result(ObjectMapper mapper, String id, Object result) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", mapper.readValue(id, Object.class), "result", result));
    }
}
