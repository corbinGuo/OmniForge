package com.omniforge.gateway.qq;

import com.omniforge.gateway.ImMessageRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * QQ 官方机器人装配：配置存储、token 管理、适配器与回复通道无条件注册
 * （enabled=false 时接收器不启动、无副作用）；WebSocket 与 Webhook 接收器
 * 在 start() 内按 {@code qq-im.yml} 的 enabled 门控（GUI/Headless 均可用）。
 */
@AutoConfiguration(afterName = "com.omniforge.gateway.ImGatewayAutoConfiguration")
public class QqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QqSettingsStore qqSettingsStore() {
        return new QqSettingsStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public QqAccessTokenProvider qqAccessTokenProvider(QqSettingsStore qqSettingsStore) {
        return new QqAccessTokenProvider(qqSettingsStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public QQOfficialAdapter qqOfficialAdapter() {
        return new QQOfficialAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public QQOfficialReplySender qqOfficialReplySender(QqSettingsStore qqSettingsStore,
                                                       QqAccessTokenProvider qqAccessTokenProvider) {
        return new QQOfficialReplySender(qqSettingsStore, qqAccessTokenProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public QqWebSocketReceiver qqWebSocketReceiver(QqSettingsStore qqSettingsStore,
                                                   QQOfficialAdapter qqOfficialAdapter,
                                                   QqAccessTokenProvider qqAccessTokenProvider,
                                                   ObjectProvider<ImMessageRouter> routers) {
        return new QqWebSocketReceiver(qqSettingsStore, qqOfficialAdapter, qqAccessTokenProvider, routers);
    }

    @Bean
    @ConditionalOnMissingBean
    public QqWebhookReceiver qqWebhookReceiver(QqSettingsStore qqSettingsStore,
                                               QQOfficialAdapter qqOfficialAdapter,
                                               ObjectProvider<ImMessageRouter> routers) {
        return new QqWebhookReceiver(qqSettingsStore, qqOfficialAdapter, routers);
    }
}
