package com.omniforge.knowledge.store;

/**
 * 向量数学工具（余弦相似度等），供各向量存储实现共用。
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /**
     * 余弦相似度（向量未归一化时通用）。
     *
     * @param a 向量 a
     * @param b 向量 b（维度须一致，否则按较短维度计算）
     * @return [-1, 1] 区间；任一向量为零向量时返回 0
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
