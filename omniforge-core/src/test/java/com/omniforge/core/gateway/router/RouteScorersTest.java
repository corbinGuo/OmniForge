package com.omniforge.core.gateway.router;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 复杂度三信号打分测试：Token 长度 / 关键词 / Embedding 相似度 */
class RouteScorersTest {

    @Test
    void token长度短文本低分长文本满分() {
        TokenLengthScorer scorer = new TokenLengthScorer();
        RoutingSettings settings = RoutingSettings.defaults();
        assertThat(scorer.score("", settings)).isEqualTo(0.0);
        assertThat(scorer.score("你好", settings)).isLessThan(0.1);
        assertThat(scorer.score("字".repeat(1000), settings)).isEqualTo(1.0); // 1000 token > 800 封顶
    }

    @Test
    void 关键词命中计数归一化() {
        KeywordScorer scorer = new KeywordScorer();
        RoutingSettings settings = new RoutingSettings(true, "a", "b", 0.6, Map.of(),
                List.of("分析", "代码", "优化"), List.of());
        assertThat(scorer.score("今天天气怎么样", settings)).isEqualTo(0.0);
        assertThat(scorer.score("帮我分析一下", settings)).isEqualTo(1.0 / 3.0);
        assertThat(scorer.score("请分析这段代码并优化", settings)).isEqualTo(1.0); // 3 命中满分
        assertThat(scorer.score("", settings)).isEqualTo(0.0);
    }

    @Test
    void 关键词表为空信号记零() {
        KeywordScorer scorer = new KeywordScorer();
        RoutingSettings settings = new RoutingSettings(true, "a", "b", 0.6, Map.of(),
                List.of(), List.of());
        assertThat(scorer.score("随便什么文本", settings)).isEqualTo(0.0);
    }

    @Test
    void embedding相似度取样本平均余弦且样本向量缓存() {
        AtomicInteger calls = new AtomicInteger();
        RouteEmbeddingProvider provider = text -> {
            calls.incrementAndGet();
            return switch (text) {
                case "exemplarA" -> new float[]{1.0f, 0.0f};
                case "exemplarB" -> new float[]{0.0f, 1.0f};
                default -> new float[]{1.0f, 1.0f};
            };
        };
        EmbeddingSimilarityScorer scorer = new EmbeddingSimilarityScorer(provider);
        RoutingSettings settings = new RoutingSettings(true, "a", "b", 0.6, Map.of(),
                List.of(), List.of("exemplarA", "exemplarB"));

        // query=exemplarA：cos(A,A)=1, cos(A,B)=0 → 0.5
        assertThat(scorer.score("exemplarA", settings)).isEqualTo(0.5);
        // query=其他：(cos(C,A)+cos(C,B))/2 = (0.7071+0.7071)/2 ≈ 0.7071
        assertThat(scorer.score("other", settings)).isBetween(0.7, 0.71);
        // 样本只向量化一次（2 样本）+ 2 次查询 = 4 次调用
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void embedding引擎异常时信号记零() {
        RouteEmbeddingProvider provider = text -> {
            throw new IllegalStateException("引擎未就绪");
        };
        EmbeddingSimilarityScorer scorer = new EmbeddingSimilarityScorer(provider);
        RoutingSettings settings = new RoutingSettings(true, "a", "b", 0.6, Map.of(),
                List.of(), List.of("exemplarA"));
        assertThat(scorer.score("任意文本", settings)).isEqualTo(0.0);
    }

    @Test
    void 样本为空时embedding信号记零() {
        EmbeddingSimilarityScorer scorer = new EmbeddingSimilarityScorer(text -> new float[]{1.0f});
        RoutingSettings settings = new RoutingSettings(true, "a", "b", 0.6, Map.of(),
                List.of(), List.of()); // 显式空样本（defaults 自带样本）
        assertThat(scorer.score("任意文本", settings)).isEqualTo(0.0);
    }
}
