package com.omniforge.knowledge.embedding;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 向量化引擎 SPI（需求 4.4：DJL + HuggingFace 模型）。
 *
 * <p>默认实现 {@code DjlEmbeddingEngine}（sentence-transformers/all-MiniLM-L6-v2，ONNX）；
 * 支持经配置切换 Ollama 等远程 Embedding 服务（决议 #8）。</p>
 */
public interface EmbeddingEngine {

    /** 将文本向量化（阻塞；模型未就绪时抛 {@link IllegalStateException}） */
    float[] embed(String text);

    /** 引擎是否就绪（模型文件已下载且可加载） */
    boolean isAvailable();

    /** 引擎与模型描述（如 "DJL/ONNX all-MiniLM-L6-v2 384维"） */
    String description();

    /** 输出向量维度 */
    int dimensions();

    /** 经 ServiceLoader 发现引擎（未配置时返回 Optional.empty） */
    static Optional<EmbeddingEngine> discover() {
        return ServiceLoader.load(EmbeddingEngine.class).findFirst();
    }
}
