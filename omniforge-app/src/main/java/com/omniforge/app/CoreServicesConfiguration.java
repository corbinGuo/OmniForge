package com.omniforge.app;

import com.omniforge.core.eula.EulaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 应用级核心服务装配：EULA 同意状态（Phase 4）。
 * 经 EmptyConfiguration 引入（GUI 与 Headless 两条启动路径共用）。
 */
@Configuration(proxyBeanMethods = false)
public class CoreServicesConfiguration {

    @Bean
    public EulaService eulaService() {
        return new EulaService(configDir());
    }

    public static Path configDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Path.of(appData) : Path.of(userHome);
            return base.resolve("OmniForge");
        }
        return Path.of(userHome, ".omniforge");
    }
}
