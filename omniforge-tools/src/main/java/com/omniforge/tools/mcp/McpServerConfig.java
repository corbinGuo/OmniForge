package com.omniforge.tools.mcp;

import java.util.List;

/**
 * 单个 MCP 服务器配置（mcp.yml 条目，需求 4.5 双轨制 MCP 标准接入）。
 *
 * @param name      服务器唯一标识（工具名前缀来源）
 * @param transport 传输方式：{@link #TRANSPORT_STDIO}（本地进程）| {@link #TRANSPORT_HTTP}（streamable HTTP 远程服务）
 * @param command   stdio：可执行文件（如 npx）；http 下忽略
 * @param args      stdio：启动参数；http 下忽略
 * @param url       http：服务端点；stdio 下忽略
 * @param enabled   每服务器独立开关（默认 false，安全底线：仅挂载可信服务器）
 */
public record McpServerConfig(String name, String transport, String command,
                              List<String> args, String url, boolean enabled) {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";

    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 服务器名称不能为空");
        }
        if (!TRANSPORT_STDIO.equals(transport) && !TRANSPORT_HTTP.equals(transport)) {
            throw new IllegalArgumentException("不支持的传输方式: " + transport + "（可选 stdio/http）");
        }
        if (TRANSPORT_STDIO.equals(transport) && (command == null || command.isBlank())) {
            throw new IllegalArgumentException("stdio 传输必须配置 command");
        }
        if (TRANSPORT_HTTP.equals(transport) && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("http 传输必须配置 url");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** 便捷构造：全部字段（不校验开关） */
    public static McpServerConfig stdio(String name, String command, List<String> args, boolean enabled) {
        return new McpServerConfig(name, TRANSPORT_STDIO, command, args, null, enabled);
    }

    /** 便捷构造：streamable HTTP 服务器 */
    public static McpServerConfig http(String name, String url, boolean enabled) {
        return new McpServerConfig(name, TRANSPORT_HTTP, null, List.of(), url, enabled);
    }
}
