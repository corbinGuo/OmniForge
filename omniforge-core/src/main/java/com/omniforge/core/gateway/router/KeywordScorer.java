package com.omniforge.core.gateway.router;

import java.util.List;

/**
 * 关键词信号：routing.yml 关键词表命中计数。命中 3 个及以上计满分（1.0）。
 * 关键词表为空时信号记 0（路由权重自动重归一化）。
 */
public final class KeywordScorer implements RouteScorer {

    /** 满分命中数 */
    static final double FULL_SCORE_HITS = 3.0;

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public double score(String userText, RoutingSettings settings) {
        List<String> keywords = settings.getKeywords();
        if (userText == null || userText.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0.0;
        }
        long hits = keywords.stream().filter(userText::contains).count();
        return Math.min(1.0, hits / FULL_SCORE_HITS);
    }
}
