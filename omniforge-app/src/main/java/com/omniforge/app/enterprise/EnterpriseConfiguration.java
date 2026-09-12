package com.omniforge.app.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 企业版客户端装配（C-tier 批次 4-2）：
 * --mode=enterprise 启动时设置 omniforge.enterprise.enabled=true 触发本装配，
 * 提供会话管理器（服务端地址 enterprise.yml 优先，环境变量 OMNI_ENTERPRISE_URL 兜底）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "omniforge.enterprise", name = "enabled", havingValue = "true")
public class EnterpriseConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EnterpriseSettingsStore enterpriseSettingsStore() {
        return new EnterpriseSettingsStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public EnterpriseSessionManager enterpriseSessionManager(EnterpriseSettingsStore store) {
        return new EnterpriseSessionManager(resolveServerUrl(store));
    }

    /** ui 桥接（OmniForgeApplication 按类型拾取；未注入时走单机模式） */
    @Bean
    @ConditionalOnMissingBean
    public com.omniforge.ui.enterprise.EnterpriseBridge enterpriseBridge(
            EnterpriseSessionManager manager, EnterpriseSettingsStore store) {
        return new EnterpriseBridgeImpl(manager, store, settingsFile(), resolveServerUrl(store));
    }

    private static String resolveServerUrl(EnterpriseSettingsStore store) {
        String serverUrl = store.load(settingsFile()).serverUrl();
        if (serverUrl.isBlank()) {
            serverUrl = System.getenv("OMNI_ENTERPRISE_URL");
        }
        if (serverUrl == null || serverUrl.isBlank()) {
            serverUrl = "http://localhost:8080"; // 登录框可手输并保存
        }
        return serverUrl;
    }

    private static Path settingsFile() {
        return defaultConfigDir().resolve("enterprise.yml");
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
