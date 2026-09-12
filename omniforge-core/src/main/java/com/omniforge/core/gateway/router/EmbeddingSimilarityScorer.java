package com.omniforge.core.gateway.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Embedding 相似度信号：输入与 routing.yml 复杂问题样本（exemplars）的
 * 平均余弦相似度。样本向量首次计算后缓存（样本静态不随请求变化）。
 *
 * <p>provider 缺失/引擎未就绪/样本为空 → 信号记 0（权重自动重归一化）。</p>
 */
public final class EmbeddingSimilarityScorer implements RouteScorer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSimilarityScorer.class);

    private final RouteEmbeddingProvider provider;
    /** 样本向量缓存（[样本数][维度]） */
    private final AtomicReference<float[][]> cachedExemplars = new AtomicReference<>();

    public EmbeddingSimilarityScorer(RouteEmbeddingProvider provider) {
        this.provider = provider;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public double score(String userText, RoutingSettings settings) {
        List<String> exemplars = settings.getExemplars();
        if (userText == null || userText.isBlank() || exemplars.isEmpty()) {
            return 0.0;
        }
        try {
            float[][] sampleVectors = exemplarVectors(exemplars);
            float[] query = provider.embed(userText);
            double sum = 0;
            for (float[] sample : sampleVectors) {
                sum += cosine(query, sample);
            }
            return Math.max(0.0, sum / sampleVectors.length);
        } catch (Exception e) {
            log.debug("Embedding 相似度信号不可用（已跳过）：{}", e.getMessage());
            return 0.0;
        }
    }

    private float[][] exemplarVectors(List<String> exemplars) {
        float[][] cached = cachedExemplars.get();
        if (cached != null) {
            return cached;
        }
        float[][] vectors = new float[exemplars.size()][];
        for (int i = 0; i < exemplars.size(); i++) {
            vectors[i] = provider.embed(exemplars.get(i));
        }
        cachedExemplars.compareAndSet(null, vectors);
        return cachedExemplars.get();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
