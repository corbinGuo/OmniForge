package com.omniforge.core.gateway.router;

/**
 * 路由 Embedding 提供者 SPI：core 不依赖 knowledge 模块，
 * 由装配层（app）把知识库 EmbeddingEngine 包装后注入；
 * 未注入或引擎未就绪时 Embedding 相似度信号自动跳过。
 */
@FunctionalInterface
public interface RouteEmbeddingProvider {

    /**
     * 文本向量化。
     *
     * @param text 待向量化文本
     * @return 向量（维度任意，比较双方须一致）
     * @throws IllegalStateException 引擎未就绪时抛出（调用方跳过该信号）
     */
    float[] embed(String text);
}
