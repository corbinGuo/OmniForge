package com.omniforge.core.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 持久化配置（前缀 {@code omniforge.persistence}）。
 *
 * <p>单机版使用 SQLite（WAL 模式）；企业版服务端使用 PostgreSQL（另一 schema，决议 #7 彻底分离）。
 * database-file 特殊值 {@code :memory:} 供测试使用（进程内共享缓存库）。</p>
 */
@ConfigurationProperties(prefix = "omniforge.persistence")
public class PersistenceProperties {

    /**
     * SQLite 数据库文件路径，默认 <配置目录>/omniforge.db。
     * 采用 String 而非 Path：特殊值 {@code :memory:}（测试用）无法在 Windows 上绑定为 Path。
     */
    private String databaseFile = defaultConfigDir().resolve("omniforge.db").toString();

    /** 是否启用 WAL 模式（默认开启，需求 3 数据层） */
    private boolean walEnabled = true;

    /** 是否启用外键约束（默认开启） */
    private boolean foreignKeysEnabled = true;

    public String getDatabaseFile() {
        return databaseFile;
    }

    public void setDatabaseFile(String databaseFile) {
        this.databaseFile = databaseFile;
    }

    public boolean isWalEnabled() {
        return walEnabled;
    }

    public void setWalEnabled(boolean walEnabled) {
        this.walEnabled = walEnabled;
    }

    public boolean isForeignKeysEnabled() {
        return foreignKeysEnabled;
    }

    public void setForeignKeysEnabled(boolean foreignKeysEnabled) {
        this.foreignKeysEnabled = foreignKeysEnabled;
    }

    /** 是否为内存库（测试专用值 :memory:） */
    public boolean isInMemory() {
        return ":memory:".equals(databaseFile);
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
