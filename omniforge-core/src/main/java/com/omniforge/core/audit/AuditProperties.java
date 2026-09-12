package com.omniforge.core.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 审计日志配置（application.yml 前缀 omniforge.audit）。
 * 默认存储于配置目录 logs/audit/ 下，按天一个 JSONL 文件。
 */
@ConfigurationProperties(prefix = "omniforge.audit")
public class AuditProperties {

    /** 是否启用审计日志 */
    private boolean enabled = true;

    /** 审计目录（JSONL 按天分文件） */
    private Path directory = defaultConfigDir().resolve("logs").resolve("audit");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
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
