package com.omniforge.tools.mcp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 设置运行时持有器：配置中心保存 mcp.yml 后经 {@link #update} 热生效
 * （{@link McpClientManager} 监听变更，重建服务器连接，无需重启）。
 */
public class McpSettingsHolder {

    private final AtomicReference<McpSettings> settings;

    public McpSettingsHolder(McpSettings initial) {
        this.settings = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public McpSettings current() {
        return settings.get();
    }

    public void update(McpSettings newSettings) {
        settings.set(Objects.requireNonNull(newSettings, "newSettings"));
    }
}
