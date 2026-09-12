package com.omniforge.tools.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MCP 接入装配级属性（前缀 {@code omniforge.mcp}）。
 * 默认 mcp.yml 路径与其他配置一致：Linux {@code ~/.omniforge/mcp.yml}，
 * Windows {@code %APPDATA%\OmniForge\mcp.yml}。
 */
@ConfigurationProperties(prefix = "omniforge.mcp")
public class McpProperties {

    /** mcp.yml 路径 */
    private Path configFile = defaultConfigDir().resolve("mcp.yml");

    public Path getConfigFile() {
        return configFile;
    }

    public void setConfigFile(Path configFile) {
        this.configFile = configFile;
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
