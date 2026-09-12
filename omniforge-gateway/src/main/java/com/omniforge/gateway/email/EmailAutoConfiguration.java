package com.omniforge.gateway.email;

import com.omniforge.gateway.ImMessageRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 邮件接入自动装配（Phase 3 Step 3）。
 *
 * <p>轮询器消息去向：路由骨架（幂等/白名单/日志/队列，Step 2 已建）；
 * 路由的 Agent 处理器在 Step 4 接线。路由 Bean 缺失时退化为仅日志记录。</p>
 */
@AutoConfiguration(afterName = "com.omniforge.gateway.ImGatewayAutoConfiguration")
@ConditionalOnClass(jakarta.mail.Session.class)
@EnableConfigurationProperties(EmailProperties.class)
public class EmailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmailAdapter emailAdapter(EmailProperties properties) {
        return new EmailAdapter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailReplier emailReplier(EmailProperties properties) {
        return new EmailReplier(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailPoller emailPoller(EmailProperties properties,
                                   ObjectProvider<ImMessageRouter> routers) {
        return new EmailPoller(properties, message -> {
            ImMessageRouter router = routers.getIfAvailable(() -> null);
            if (router != null) {
                router.route(message);
            } else {
                org.slf4j.LoggerFactory.getLogger(EmailAutoConfiguration.class)
                        .info("收到邮件（未接入路由骨架）：{}", message.messageId());
            }
        });
    }
}
