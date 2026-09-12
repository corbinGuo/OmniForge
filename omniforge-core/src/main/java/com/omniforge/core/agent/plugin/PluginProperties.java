package com.omniforge.core.agent.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 插件加载配置（application.yml 前缀 omniforge.plugin）。
 *
 * <p>插件目录默认为配置目录下的 plugins/（Windows %APPDATA%\OmniForge\plugins，
 * Linux ~/.omniforge/plugins），jar 放置其中即自动加载，删除即自动卸载。</p>
 */
@ConfigurationProperties(prefix = "omniforge.plugin")
public class PluginProperties {

    /** 是否启用插件加载 */
    private boolean enabled = true;

    /** 插件目录 */
    private Path pluginsDir = defaultConfigDir().resolve("plugins");

    /** 目录变化防抖（毫秒，模式同 models.yml 监听） */
    private long watchDebounceMillis = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    public long getWatchDebounceMillis() {
        return watchDebounceMillis;
    }

    public void setWatchDebounceMillis(long watchDebounceMillis) {
        this.watchDebounceMillis = watchDebounceMillis;
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
    private static Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(userHome);
            return base.resolve("OmniForge");
        }
        return Paths.get(userHome, ".omniforge");
    }
}
