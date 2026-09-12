package com.omniforge.core.gateway.router;

import com.omniforge.core.gateway.ModelRouter;
import com.omniforge.core.persistence.service.LicenseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 分层级智能路由自动装配（Pro，Batch2 升级）。
 *
 * <p><b>before ModelGatewayAutoConfiguration</b>：须先于网关装配注册
 * {@link TierAwareRouter}，使网关的 AliasModelRouter
 * （@ConditionalOnMissingBean）让位，DefaultModelGateway 注入分层路由器。</p>
 *
 * <p>Embedding 信号经 {@link RouteEmbeddingProvider} SPI 由装配层（app）
 * 注入（core 不依赖 knowledge）；未注入则跳过该信号。</p>
 */
@AutoConfiguration(beforeName = "com.omniforge.core.gateway.ModelGatewayAutoConfiguration",
        afterName = "com.omniforge.core.persistence.PersistenceAutoConfiguration")
@ConditionalOnClass({ModelRouter.class, LicenseService.class})
public class RouterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelRouter.class)
    public TierAwareRouter tierAwareRouter(LicenseService licenseService,
                                           ObjectProvider<RouteEmbeddingProvider> embeddingProvider) {
        List<RouteScorer> scorers = new ArrayList<>();
        scorers.add(new TokenLengthScorer());
        scorers.add(new KeywordScorer());
        embeddingProvider.ifAvailable(provider -> scorers.add(new EmbeddingSimilarityScorer(provider)));
        return new TierAwareRouter(licenseService, new RoutingConfigStore(),
                routingFile(), scorers);
    }

    private static Path routingFile() {
        return defaultConfigDir().resolve("routing.yml");
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
