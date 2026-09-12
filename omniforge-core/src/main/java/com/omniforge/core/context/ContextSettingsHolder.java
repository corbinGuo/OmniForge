package com.omniforge.core.context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上下文设置运行时持有器：配置中心保存后经 {@link #update} 热生效，
 * {@link ContextManager} 每次构建历史时读取当前值（无需重启）。
 */
public class ContextSettingsHolder {

    private final AtomicReference<ContextSettings> settings;

    public ContextSettingsHolder(ContextSettings initial) {
        this.settings = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public ContextSettings current() {
        return settings.get();
    }

    public void update(ContextSettings newSettings) {
        settings.set(Objects.requireNonNull(newSettings, "newSettings"));
    }
}
