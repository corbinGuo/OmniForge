package com.omniforge.core.debate;

import java.util.Map;

/**
 * 语义共识检测 SPI（需求 4.2：Embedding 计算回复相似度，>0.92 自动终止）。
 *
 * <p>core 不依赖知识库模块（避免反向依赖），Embedding 实现
 * （{@code EmbeddingConsensusDetector}）在装配层（omniforge-app）接线；
 * 未注入时共识检测自动关闭。</p>
 */
public interface ConsensusDetector {

    /**
     * 计算本轮各模型发言的语义共识度。
     *
     * @param roundOutputs 本轮各模型发言（键=别名，值=文本）
     * @return 共识度 0~1；检测器不可用（如 Embedding 引擎未就绪）时返回 null（本轮跳过检测）
     */
    Double consensusScore(Map<String, String> roundOutputs);
}
