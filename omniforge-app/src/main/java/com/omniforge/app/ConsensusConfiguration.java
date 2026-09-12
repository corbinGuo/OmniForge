package com.omniforge.app;

import com.omniforge.core.debate.ConsensusDetector;
import com.omniforge.core.gateway.router.RouteEmbeddingProvider;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配层接线：将知识库模块的 EmbeddingEngine 注入辩论引擎的共识检测 SPI 与
 * 智能路由的 Embedding 信号 SPI（core 不依赖 knowledge，交叉接线由装配层完成）。
 */
@Configuration(proxyBeanMethods = false)
public class ConsensusConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConsensusDetector consensusDetector(EmbeddingEngine embeddingEngine) {
        return new EmbeddingConsensusDetector(embeddingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public RouteEmbeddingProvider routeEmbeddingProvider(EmbeddingEngine embeddingEngine) {
        return new RoutingEmbeddingProvider(embeddingEngine);
    }
}
