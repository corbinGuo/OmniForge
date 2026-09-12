package com.omniforge.app;

import com.omniforge.core.debate.ConsensusDetector;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义共识检测器（需求 4.2：Embedding 计算回复相似度，>0.92 自动终止）。
 *
 * <p>装配层实现：复用知识库模块的 EmbeddingEngine（DJL/ONNX），
 * 计算本轮各模型发言的平均两两余弦相似度；引擎未就绪时返回 null（本轮跳过检测）。
 * 模型权重未下载时自动降级为关闭共识熔断，不阻塞辩论。</p>
 */
public class EmbeddingConsensusDetector implements ConsensusDetector {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConsensusDetector.class);

    private final EmbeddingEngine embeddingEngine;

    public EmbeddingConsensusDetector(EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
    }

    @Override
    public Double consensusScore(Map<String, String> roundOutputs) {
        if (!embeddingEngine.isAvailable()) {
            log.debug("Embedding 引擎未就绪，本轮跳过共识检测");
            return null;
        }
        List<float[]> vectors = new ArrayList<>();
        for (String text : roundOutputs.values()) {
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                vectors.add(embeddingEngine.embed(text));
            } catch (Exception e) {
                log.warn("向量化失败，跳过共识检测：{}", e.getMessage());
                return null;
            }
        }
        if (vectors.size() < 2) {
            return null;
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < vectors.size(); i++) {
            for (int j = i + 1; j < vectors.size(); j++) {
                sum += cosine(vectors.get(i), vectors.get(j));
                pairs++;
            }
        }
        return pairs == 0 ? null : sum / pairs;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
