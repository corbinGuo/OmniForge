package com.omniforge.gateway.napcat;

import com.omniforge.gateway.ImMessageRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * NapCat（QQ · OneBot 11）装配：适配器与回复通道无条件注册（disabled 时不接收事件，
 * 无副作用）；事件接收器按 {@code omniforge.im.napcat.enabled=true} 门控启动
 * （GUI/Headless 均可用——补充建议 #1）。
 */
@AutoConfiguration(afterName = "com.omniforge.gateway.ImGatewayAutoConfiguration")
@EnableConfigurationProperties(NapCatProperties.class)
public class NapCatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NapCatAdapter napCatAdapter(NapCatProperties properties) {
        return new NapCatAdapter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public NapCatReplySender napCatReplySender(NapCatProperties properties) {
        return new NapCatReplySender(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.im.napcat", name = "enabled", havingValue = "true")
    public ImEventHttpReceiver imEventHttpReceiver(NapCatProperties properties, NapCatAdapter adapter,
                                                   ObjectProvider<ImMessageRouter> routers) {
        return new ImEventHttpReceiver(properties, adapter, routers);
    }
}
