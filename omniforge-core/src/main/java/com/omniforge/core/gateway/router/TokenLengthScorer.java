package com.omniforge.core.gateway.router;

import com.omniforge.core.context.TokenEstimator;

/**
 * Token 长度信号：输入越长问题越复杂。800 token 计满分（1.0），线性归一化。
 * token 估计复用 {@link TokenEstimator}（CJK 1 字≈1 token，拉丁 4 字符≈1 token）。
 */
public final class TokenLengthScorer implements RouteScorer {

    /** 满分 token 数（约 3200 汉字） */
    static final double FULL_SCORE_TOKENS = 800.0;

    @Override
    public String name() {
        return "tokenLength";
    }

    @Override
    public double score(String userText, RoutingSettings settings) {
        if (userText == null || userText.isBlank()) {
            return 0.0;
        }
        return Math.min(1.0, TokenEstimator.estimate(userText) / FULL_SCORE_TOKENS);
    }
}
