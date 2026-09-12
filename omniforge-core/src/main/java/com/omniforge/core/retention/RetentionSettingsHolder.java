package com.omniforge.core.retention;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保留策略运行时持有器（热生效，模式同 ToolsSettingsHolder）：
 * 装配时 load 一次，配置中心保存后 {@link #update} 即时生效。
 */
public class RetentionSettingsHolder {

    private final AtomicReference<RetentionSettings> current;

    public RetentionSettingsHolder(RetentionSettings initial) {
        this.current = new AtomicReference<>(initial == null ? RetentionSettings.defaults() : initial);
    }

    /** 当前生效设置 */
    public RetentionSettings current() {
        return current.get();
    }

    /** 热更新（配置保存后调用） */
    public void update(RetentionSettings settings) {
        current.set(Objects.requireNonNull(settings, "settings"));
    }
}
