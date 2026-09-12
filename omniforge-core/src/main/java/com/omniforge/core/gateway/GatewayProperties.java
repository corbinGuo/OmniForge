package com.omniforge.core.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 网关配置项（前缀 {@code omniforge.gateway}），可通过 application.yml / 系统属性 / 环境变量覆盖。
 *
 * <p>默认配置目录遵循需求命名体系：Linux 为 {@code ~/.omniforge}，
 * Windows 为 {@code %APPDATA%\OmniForge}。</p>
 */
@ConfigurationProperties(prefix = "omniforge.gateway")
public class GatewayProperties {

    /** models.yml 路径，默认 {@code <配置目录>/models.yml} */
    private Path configFile = defaultConfigDir().resolve("models.yml");

    /** 是否开启配置热重载监听（默认开启） */
    private boolean watchEnabled = true;

    /** 热重载防抖时间（毫秒） */
    private long watchDebounceMillis = 300;

    public Path getConfigFile() {
        return configFile;
    }

    public void setConfigFile(Path configFile) {
        this.configFile = configFile;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }

    public long getWatchDebounceMillis() {
        return watchDebounceMillis;
    }

    public void setWatchDebounceMillis(long watchDebounceMillis) {
        this.watchDebounceMillis = watchDebounceMillis;
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge */
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
