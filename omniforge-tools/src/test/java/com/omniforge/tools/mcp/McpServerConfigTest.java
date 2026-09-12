package com.omniforge.tools.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpServerConfigTest {

    @Test
    void stdio配置需要command() {
        assertThrows(IllegalArgumentException.class,
                () -> McpServerConfig.stdio("s1", "", List.of(), true));
        McpServerConfig config = McpServerConfig.stdio("s1", "npx", List.of("-y"), true);
        assertEquals(McpServerConfig.TRANSPORT_STDIO, config.transport());
        assertEquals(List.of("-y"), config.args());
    }

    @Test
    void http配置需要url() {
        assertThrows(IllegalArgumentException.class,
                () -> McpServerConfig.http("s1", "", true));
        McpServerConfig config = McpServerConfig.http("s1", "http://localhost:9000/mcp", false);
        assertEquals(McpServerConfig.TRANSPORT_HTTP, config.transport());
        assertEquals(false, config.enabled());
    }

    @Test
    void 非法传输方式与空名称被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig("s1", "sse", "npx", List.of(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig("  ", McpServerConfig.TRANSPORT_HTTP, null, List.of(),
                        "http://x", true));
    }
}
