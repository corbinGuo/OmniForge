package com.omniforge.core.context;

import com.omniforge.core.gateway.ModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 上下文管理自动装配（需求 4.2）：
 * 提供 {@link ContextSettingsHolder}（热生效持有器）与 {@link ContextManager}。
 *
 * <p>摘要器依赖模型网关：网关未装配（理论上不会发生，核心装配恒有网关）时
 * 摘要能力关闭，滑动窗口裁剪不受影响。</p>
 */
@AutoConfiguration(afterName = "com.omniforge.core.gateway.ModelGatewayAutoConfiguration")
@ConditionalOnClass({ChatModel.class})
@EnableConfigurationProperties(ContextProperties.class)
public class ContextManagerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ContextSettingsHolder contextSettingsHolder(ContextProperties properties) {
        return new ContextSettingsHolder(ContextSettings.from(properties));
    }

    @Bean
    @ConditionalOnMissingBean(ContextManager.class)
    public ContextManager contextManager(ContextSettingsHolder settingsHolder,
                                         ContextProperties properties,
                                         ObjectProvider<ModelGateway> gateways) {
        ModelGateway gateway = gateways.getIfAvailable(() -> null);
        HistorySummarizer summarizer = gateway == null ? null
                : new GatewayHistorySummarizer(gateway, settingsHolder);
        return new DefaultContextManager(settingsHolder, summarizer,
                properties.getCapacity(), Duration.ofHours(properties.getTtlHours()));
    }
}
