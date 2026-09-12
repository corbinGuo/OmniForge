package com.omniforge.tools.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final McpSettingsStore store = new McpSettingsStore();

    @Test
    void 文件缺失返回空设置() {
        McpSettings settings = store.load(tempDir.resolve("missing.yml"));
        assertTrue(settings.servers().isEmpty());
    }

    @Test
    void 文件损坏返回空设置() throws Exception {
        Path file = tempDir.resolve("mcp.yml");
        Files.writeString(file, "servers: [未闭合");
        assertTrue(store.load(file).servers().isEmpty());
    }

    @Test
    void 读写往返() throws Exception {
        Path file = tempDir.resolve("mcp.yml");
        McpSettings out = new McpSettings(List.of(
                McpServerConfig.stdio("filesystem", "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem", "C:/data"), true),
                McpServerConfig.http("remote", "http://localhost:9000/mcp", false)));
        store.save(file, out);

        McpSettings loaded = store.load(file);
        assertEquals(2, loaded.servers().size());
        McpServerConfig stdio = loaded.servers().get(0);
        assertEquals("filesystem", stdio.name());
        assertEquals(McpServerConfig.TRANSPORT_STDIO, stdio.transport());
        assertEquals("npx", stdio.command());
        assertEquals(3, stdio.args().size());
        assertTrue(stdio.enabled());
        McpServerConfig http = loaded.servers().get(1);
        assertEquals("http://localhost:9000/mcp", http.url());
        assertTrue(!http.enabled());
    }
}
