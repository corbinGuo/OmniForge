package com.omniforge.tools;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具开关运行时持有器：配置中心保存后经 {@link #update} 热生效，
 * 各内置工具在每次执行时读取当前值（无需重建工具实例）。
 */
public class ToolsSettingsHolder {

    private final AtomicReference<ToolsSettings> settings;

    public ToolsSettingsHolder(ToolsSettings initial) {
        this.settings = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public ToolsSettings current() {
        return settings.get();
    }

    public void update(ToolsSettings newSettings) {
        settings.set(Objects.requireNonNull(newSettings, "newSettings"));
    }
}
