package com.omniforge.app.headless;

import com.omniforge.core.gateway.ModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Headless 装配（仅 --headless 启动分支引入，GUI 路径不加载）：
 * 健康检查/指标 HTTP 服务（JDK 内置 HttpServer，端口默认 5119）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HeadlessProperties.class)
public class HeadlessConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HeadlessHttpServer headlessHttpServer(HeadlessProperties properties,
                                                 ModelGateway modelGateway, DataSource dataSource) {
        return new HeadlessHttpServer(properties, modelGateway, dataSource);
    }
}
