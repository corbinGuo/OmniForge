package com.omniforge.app.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.tools.ToolsSettingsHolder;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OmniForge 本地 MCP 服务端（P1-4 反向，v1 = stdio）。
 *
 * <p>把 {@link ToolRegistry} 可暴露工具以 MCP server 跑在 stdin/stdout。实现为
 * 自研最小 MCP stdio 服务（JSON-RPC 2.0 + Content-Length 帧），只覆盖外部客户端
 * 需要的子集：initialize / notifications / tools.list / tools.call / ping——
 * 与「钉钉纯 HTTP 零 SDK」先例一致，传输与生命周期完全自控（读到 stdin EOF 即退出），
 * 不依赖 SDK server provider 的启动语义。stdout 只写协议帧；日志走 stderr/文件
 * （Launcher 设 omniforge.console.target=SYSTEM_ERR）。</p>
 */
public final class OmniForgeMcpServer {

    private static final Logger log = LoggerFactory.getLogger(OmniForgeMcpServer.class);

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private OmniForgeMcpServer() {
    }

    /** 在 System.in/out 上运行直到 EOF。 */
    public static void run(ToolRegistry registry, ToolsSettingsHolder tools,
                           ConfigurableApplicationContext context) throws Exception {
        List<Tool> selected = McpToolCatalog.select(registry, tools);
        Map<String, Tool> byName = McpToolCatalog.indexByName(selected);
        io.modelcontextprotocol.json.McpJsonMapper mapper =
                io.modelcontextprotocol.json.McpJsonMapper.createDefault();
        List<McpSchema.Tool> mcpTools = selected.stream()
                .map(tool -> McpToolCatalog.toMcpTool(tool, mapper))
                .toList();
        log.info("OmniForge MCP server 已启动（stdio），暴露 {} 个工具：{}", mcpTools.size(),
                byName.keySet().stream().sorted().toList());
        serve(System.in, System.out, mcpTools, byName, new ObjectMapper());
        context.close();
        log.info("OmniForge MCP server 已退出（stdin EOF）");
    }

    /** 协议主循环（可测：注入流与 mapper） */
    static void serve(InputStream in, OutputStream out, List<McpSchema.Tool> mcpTools,
                      Map<String, Tool> byName, ObjectMapper json) {
        try {
            McpSession session = new McpSession(in, out, json, mcpTools, byName);
            session.loop();
        } catch (IOException e) {
            log.debug("MCP stdio 结束：{}", e.getMessage());
        }
    }

    /** 最小 stdio 会话：逐帧读（header+body）→ 分发 → 写响应；EOF 返回 */
    private static final class McpSession {

        private final InputStream in;
        private final OutputStream out;
        private final ObjectMapper json;
        private final List<McpSchema.Tool> mcpTools;
        private final Map<String, Tool> byName;
        private String protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.get(0);

        McpSession(InputStream in, OutputStream out, ObjectMapper json,
                   List<McpSchema.Tool> mcpTools, Map<String, Tool> byName) {
            this.in = in;
            this.out = out;
            this.json = json;
            this.mcpTools = mcpTools;
            this.byName = byName;
        }

        void loop() throws IOException {
            byte[] frame;
            while ((frame = readFrame()) != null) {
                handleFrame(frame);
            }
        }

        private void handleFrame(byte[] frame) {
            Map<String, Object> msg;
            try {
                msg = json.readValue(frame, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                log.warn("MCP 帧解析失败：{}", e.getMessage());
                return;
            }
            String method = msg.get("method") instanceof String m ? m : null;
            boolean hasId = msg.containsKey("id");
            if (method == null) {
                return; // 非法/响应帧，忽略
            }
            Object id = msg.get("id");
            if (!hasId) {
                // 客户端通知：无需响应
                return;
            }
            Map<String, Object> params = uncheckedParams(msg.get("params"));
            Map<String, Object> result;
            switch (method) {
                case "initialize" -> {
                    protocolVersion = negotiate(params.get("protocolVersion"));
                    Map<String, Object> capabilities = new LinkedHashMap<>();
                    capabilities.put("tools", Map.of());
                    Map<String, Object> serverInfo = new LinkedHashMap<>();
                    serverInfo.put("name", "OmniForge");
                    serverInfo.put("version", "0.1.0");
                    result = new LinkedHashMap<>();
                    result.put("protocolVersion", protocolVersion);
                    result.put("capabilities", capabilities);
                    result.put("serverInfo", serverInfo);
                }
                case "tools/list" -> {
                    List<Map<String, Object>> tools = new ArrayList<>(mcpTools.size());
                    for (McpSchema.Tool t : mcpTools) {
                        tools.add(json.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        }));
                    }
                    result = Map.of("tools", tools);
                }
                case "tools/call" -> {
                    String name = params.get("name") instanceof String s ? s : null;
                    Map<String, Object> arguments = uncheckedParams(params.get("arguments"));
                    Tool tool = name == null ? null : byName.get(name);
                    if (tool == null) {
                        result = Map.of("content", List.of(textContent("ERROR: 未知工具: " + name)),
                                "isError", true);
                    } else {
                        McpSchema.CallToolResult call = McpToolCatalog.invoke(tool, arguments);
                        result = json.convertValue(call,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                    }
                }
                case "ping" -> result = Map.of();
                default -> {
                    writeError(id, -32601, "Method not found: " + method);
                    return;
                }
            }
            writeResult(id, result);
        }

        private String negotiate(Object clientVersion) {
            if (clientVersion instanceof String v && SUPPORTED_PROTOCOL_VERSIONS.contains(v)) {
                return v;
            }
            return SUPPORTED_PROTOCOL_VERSIONS.get(0);
        }

        private void writeResult(Object id, Map<String, Object> result) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("result", result);
            writeFrame(msg);
        }

        private void writeError(Object id, int code, String message) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("error", Map.of("code", code, "message", message));
            writeFrame(msg);
        }

        private void writeFrame(Map<String, Object> msg) {
            try {
                byte[] body = json.writeValueAsBytes(msg);
                out.write(("Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                log.warn("MCP 写响应失败：{}", e.getMessage());
            }
        }

        /** 读一帧；EOF（无更多输入）返回 null */
        private byte[] readFrame() throws IOException {
            int length = readContentLength();
            if (length < 0) {
                return null;
            }
            byte[] body = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(body, off, length - off);
                if (n < 0) {
                    return null;
                }
                off += n;
            }
            return body;
        }

        private int readContentLength() throws IOException {
            int length = -1;
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return -1;
                }
                if (b == '\n') {
                    String text = line.toString().trim();
                    line.setLength(0);
                    if (text.isEmpty()) {
                        return length >= 0 ? length : 0;
                    }
                    if (text.toLowerCase().startsWith("content-length:")) {
                        try {
                            length = Integer.parseInt(text.substring("content-length:".length()).trim());
                        } catch (NumberFormatException e) {
                            length = 0;
                        }
                    }
                } else if (b != '\r') {
                    line.append((char) b);
                }
            }
        }

        private static Map<String, Object> textContent(String text) {
            return Map.of("type", "text", "text", text == null ? "" : text);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> uncheckedParams(Object params) {
            return params instanceof Map ? (Map<String, Object>) params : Map.of();
        }
    }
}
