package com.omniforge.core.gateway.router;

import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.ModelConfig;
import com.omniforge.core.gateway.ModelGatewayConfig;
import com.omniforge.core.persistence.service.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TierAwareRouterTest {

    @TempDir
    Path tempDir;

    private LicenseService licenseService;
    private TierAwareRouter router;
    private Path file;
    private ModelGatewayConfig config;

    private ModelConfig model(String alias, Integer tier) {
        ModelConfig m = new ModelConfig();
        m.setAlias(alias);
        m.setProvider("p");
        m.setModelId(alias);
        m.setTier(tier);
        return m;
    }

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        when(licenseService.isPro()).thenReturn(true);
        file = tempDir.resolve("routing.yml");
        router = new TierAwareRouter(licenseService, new RoutingConfigStore(), file,
                List.of(new TokenLengthScorer(), new KeywordScorer()));
        config = mock(ModelGatewayConfig.class);
        when(config.getModels()).thenReturn(List.of(
                model("a1", 1), model("a2", 2), model("a3", 3), model("a4", 4), model("a5", 5)));
        when(config.getDefaultModel()).thenReturn("def");
        when(config.findModel("a1")).thenReturn(model("a1", 1));
        when(config.findModel("a2")).thenReturn(model("a2", 2));
        when(config.findModel("a3")).thenReturn(model("a3", 3));
        when(config.findModel("a4")).thenReturn(model("a4", 4));
        when(config.findModel("a5")).thenReturn(model("a5", 5));
        when(config.findModel("def")).thenReturn(model("def", null));
    }

    private GatewayRequest request(String text, String identity) {
        return new GatewayRequest(null, null, text, null, null, List.of(), identity);
    }

    private void save(RoutingStrategy defaultStrategy, List<RoutingRule> rules) {
        RoutingConfig rc = new RoutingConfig(true, 0.6,
                RoutingConfig.defaults().getWeights(),
                RoutingConfig.defaults().getKeywords(),
                RoutingConfig.defaults().getExemplars(),
                RoutingConfig.defaultTierNames(),
                defaultStrategy, rules, null, null);
        try {
            new RoutingConfigStore().save(file, rc);
            // Windows 文件时间戳按系统 tick（约 15.6ms）量化：同一测试内连续两次 save 间隔
            // 仅微秒级，mtime 相同会让 TierAwareRouter 的 mtime 缓存误判"未变化"而沿用旧
            // 策略（全量构建偶发/必现 flake 的根因）。显式推进 mtime 保证热重载判定确定。
            Files.setLastModifiedTime(file,
                    java.nio.file.attribute.FileTime.fromMillis(mtimeBase + ++mtimeSeq));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** save 显式 mtime 基准与递增序列（同上：规避 Windows tick 量化） */
    private final long mtimeBase = System.currentTimeMillis();
    private long mtimeSeq;

    private static final String LONG_COMPLEX = "请设计并优化这段代码的架构，分析算法复杂度，评估多种方案并权衡取舍，"
            + "对比性能与可维护性，给出论文级的详细设计文档。".repeat(30);

    @Test
    void 显式指定别名直通() {
        GatewayRequest req = new GatewayRequest("a2", null, "hi", null, null, List.of(), "os:alice");
        assertEquals("a2", router.route(req, config));
    }

    @Test
    void 非Pro回退默认模型() {
        when(licenseService.isPro()).thenReturn(false);
        save(RoutingStrategy.auto(3), List.of());
        assertEquals("def", router.route(request("hi", null), config));
    }

    @Test
    void 未命中身份时用默认策略AUTO选取最低允许层级() {
        save(RoutingStrategy.auto(3), List.of());
        // 短文本复杂度低 → 3 档（允许层级最低）
        assertEquals("a3", router.route(request("hi", "os:someone"), config));
        // 长复杂文本 → 5 档
        assertEquals("a5", router.route(request(LONG_COMPLEX, "os:someone"), config));
    }

    @Test
    void 命中规则按优先级首个生效且FIXED锁档() {
        save(RoutingStrategy.auto(1), List.of(
                new RoutingRule(20, "low", "os:admin_*", RoutingStrategy.auto(2)),
                new RoutingRule(10, "top", "os:admin_*", RoutingStrategy.fixed(5)),
                new RoutingRule(5, "guest", "os:guest", RoutingStrategy.fixed(1))));
        // priority 10 先于 20 命中 → fixed 5
        assertEquals("a5", router.route(request("hi", "os:admin_x"), config));
        // 未命中 guest/低优先级 → 默认 auto(1) → 短文本选 1
        assertEquals("a1", router.route(request("hi", "os:other"), config));
    }

    @Test
    void 范围与排除策略限制候选层级() {
        save(RoutingStrategy.range(2, 4), List.of());
        assertEquals("a2", router.route(request("hi", null), config)); // 短→下限
        assertEquals("a4", router.route(request(LONG_COMPLEX, null), config)); // 复杂→上限

        save(RoutingStrategy.exclude(List.of(1, 2)), List.of());
        assertEquals("a3", router.route(request("hi", null), config)); // 排除后最低=3
    }

    @Test
    void 全库未分级时回退legacy双alias() {
        when(config.getModels()).thenReturn(List.of(model("cheap", null), model("premium", null)));
        when(config.findModel("cheap")).thenReturn(model("cheap", null));
        when(config.findModel("premium")).thenReturn(model("premium", null));
        RoutingConfig rc = new RoutingConfig(true, 0.6,
                RoutingConfig.defaults().getWeights(),
                RoutingConfig.defaults().getKeywords(),
                RoutingConfig.defaults().getExemplars(),
                RoutingConfig.defaultTierNames(),
                RoutingStrategy.auto(null), List.of(), "cheap", "premium");
        try {
            new RoutingConfigStore().save(file, rc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("premium", router.route(request(LONG_COMPLEX, null), config));
        assertEquals("cheap", router.route(request("hi", null), config));
    }

    @Test
    void 策略层级无可用模型回退默认() {
        // 允许层级最高 5，但模型最高 4 → 候选空（有分级）→ 默认
        when(config.getModels()).thenReturn(List.of(model("a1", 1), model("a4", 4)));
        when(config.findModel("a1")).thenReturn(model("a1", 1));
        when(config.findModel("a4")).thenReturn(model("a4", 4));
        save(RoutingStrategy.fixed(5), List.of());
        assertEquals("def", router.route(request("hi", null), config));
    }

    @Test
    void 默认策略不限档且复杂文本取最高档() {
        save(RoutingStrategy.auto(null), List.of());
        assertEquals("a1", router.route(request("hi", null), config));       // 短→最低档
        assertEquals("a5", router.route(request(LONG_COMPLEX, null), config)); // 复杂→最高档
    }
}
