package com.omniforge.core.gateway.router;

import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.ModelConfig;
import com.omniforge.core.gateway.ModelGatewayConfig;
import com.omniforge.core.persistence.service.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 智能路由决策测试：直通/CE 回退/复杂度分阈值/配置校验/权重归一化 */
class ComplexityModelRouterTest {

    @TempDir
    Path tempDir;

    private LicenseService licenseService;
    private ModelGatewayConfig config;
    private Path routingFile;
    private RoutingSettingsStore store;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        config = mock(ModelGatewayConfig.class);
        when(config.getDefaultModel()).thenReturn("default-model");
        when(config.findModel("cheap")).thenReturn(mock(ModelConfig.class));
        when(config.findModel("premium")).thenReturn(mock(ModelConfig.class));
        routingFile = tempDir.resolve("routing.yml");
        store = new RoutingSettingsStore();
    }

    private ComplexityModelRouter router(List<RouteScorer> scorers) {
        return new ComplexityModelRouter(licenseService, store, routingFile, scorers);
    }

    private RoutingSettings writeSettings(String simple, String advanced, double threshold,
                                          Map<String, Double> weights, List<String> keywords)
            throws Exception {
        RoutingSettings settings = new RoutingSettings(true, simple, advanced, threshold,
                weights, keywords, List.of());
        store.save(routingFile, settings);
        return settings;
    }

    private static GatewayRequest request(String alias, String text) {
        return new GatewayRequest(alias, null, text, null, null, List.of());
    }

    @Test
    void 手动指定别名直通不路由() {
        when(licenseService.isPro()).thenReturn(false); // CE 也直通
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer(), new KeywordScorer()));
        assertThat(router.route(request("gpt-x", "帮我分析复杂问题"), config)).isEqualTo("gpt-x");
    }

    @Test
    void 社区版回退默认模型() throws Exception {
        when(licenseService.isPro()).thenReturn(false);
        writeSettings("cheap", "premium", 0.6, Map.of("tokenLength", 1.0), List.of());
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer()));
        assertThat(router.route(request(null, "复杂问题文本"), config)).isEqualTo("default-model");
    }

    @Test
    void 路由关闭时回退默认模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        store.save(routingFile, new RoutingSettings(false, "cheap", "premium", 0.6,
                Map.of("tokenLength", 1.0), List.of(), List.of()));
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer()));
        assertThat(router.route(request(null, "复杂问题文本"), config)).isEqualTo("default-model");
    }

    @Test
    void 简单短文本走便宜模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        writeSettings("cheap", "premium", 0.6, Map.of("tokenLength", 0.5, "keyword", 0.5),
                List.of("分析", "代码", "优化"));
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer(), new KeywordScorer()));
        assertThat(router.route(request(null, "你好"), config)).isEqualTo("cheap");
    }

    @Test
    void 复杂文本多关键词走高级模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        // 3 关键词命中 → keyword 满分 ×0.5 = 0.5，加 token 分 0.012 → 0.512 ≥ 0.5
        writeSettings("cheap", "premium", 0.5, Map.of("tokenLength", 0.5, "keyword", 0.5),
                List.of("分析", "代码", "优化"));
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer(), new KeywordScorer()));
        assertThat(router.route(request(null, "请分析这段代码的复杂度并给出优化方案"), config))
                .isEqualTo("premium");
    }

    @Test
    void 阈值边界达线走高级模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        // 仅关键词信号（权重 1.0）：1 个命中 = 1/3 ≈ 0.3333；阈值 0.333 达线
        writeSettings("cheap", "premium", 0.333, Map.of("keyword", 1.0), List.of("分析", "代码", "优化"));
        ComplexityModelRouter router = router(List.of(new KeywordScorer()));
        assertThat(router.route(request(null, "帮我分析一下"), config)).isEqualTo("premium");
    }

    @Test
    void 路由规则模型不存在时回退默认模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        writeSettings("ghost", "premium", 0.6, Map.of("keyword", 1.0), List.of("分析"));
        ComplexityModelRouter router = router(List.of(new KeywordScorer()));
        assertThat(router.route(request(null, "帮我分析一下"), config)).isEqualTo("default-model");
    }

    @Test
    void 缺失信号权重重归一化() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        // embedding 权重最高但没有 embedding 打分器 → token/keyword 权重重归一化
        writeSettings("cheap", "premium", 0.6,
                Map.of("tokenLength", 0.1, "keyword", 0.9, "embedding", 5.0),
                List.of("分析", "代码", "优化"));
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer(), new KeywordScorer()));
        // 1 关键词命中：kw=1/3*0.9=0.3 + token≈0.003*0.1≈0.0003 → 0.3003 < 0.6 → cheap
        assertThat(router.route(request(null, "帮我分析一下"), config)).isEqualTo("cheap");
        // 3 关键词：kw=1.0*0.9=0.9 ≥ 0.6 → premium
        assertThat(router.route(request(null, "请分析这段代码并优化"), config)).isEqualTo("premium");
    }

    @Test
    void 极短输入走便宜模型() throws Exception {
        when(licenseService.isPro()).thenReturn(true);
        writeSettings("cheap", "premium", 0.6, Map.of("tokenLength", 1.0), List.of("分析"));
        ComplexityModelRouter router = router(List.of(new TokenLengthScorer(), new KeywordScorer()));
        // GatewayRequest 校验 userText 非空，用最短有效输入
        assertThat(router.route(request(null, "嗯"), config)).isEqualTo("cheap");
    }
}
