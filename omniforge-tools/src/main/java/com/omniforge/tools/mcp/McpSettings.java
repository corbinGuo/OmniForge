package com.omniforge.tools.mcp;

import java.util.List;

/**
 * MCP 服务器聚合设置（mcp.yml 内容）。
 *
 * @param servers 服务器列表（未启用项不建立连接，但保留配置）
 */
public record McpSettings(List<McpServerConfig> servers) {

    public McpSettings {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /** 空设置（无任何 MCP 服务器） */
    public static McpSettings empty() {
        return new McpSettings(List.of());
    }
}
