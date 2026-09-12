package com.omniforge.app;

import com.omniforge.core.gateway.router.RouteEmbeddingProvider;
import com.omniforge.knowledge.embedding.EmbeddingEngine;

/**
 * 装配层接线：把知识库模块的 EmbeddingEngine 包装为路由 Embedding 信号提供者
 * （core 不依赖 knowledge）。引擎未就绪时抛 IllegalStateException，
 * EmbeddingSimilarityScorer 捕获后自动跳过该信号，路由不阻塞。
 */
public class RoutingEmbeddingProvider implements RouteEmbeddingProvider {

    private final EmbeddingEngine embeddingEngine;

    public RoutingEmbeddingProvider(EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
    }

    @Override
    public float[] embed(String text) {
        if (!embeddingEngine.isAvailable()) {
            throw new IllegalStateException("Embedding 引擎未就绪");
        }
        return embeddingEngine.embed(text);
    }
}
