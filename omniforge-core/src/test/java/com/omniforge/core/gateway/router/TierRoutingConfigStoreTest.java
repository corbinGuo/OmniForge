package com.omniforge.core.gateway.router;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TierRoutingConfigStoreTest {

    @TempDir
    Path tempDir;

    private final RoutingConfigStore store = new RoutingConfigStore();

    private Path file() {
        return tempDir.resolve("routing.yml");
    }

    @Test
    void 缺失文件写入默认模板() {
        RoutingConfig cfg = store.load(file());
        assertNotNull(cfg);
        assertTrue(Files.exists(file()), "缺失应写入默认模板");
        assertEquals(5, cfg.getTierNames().size());
        assertEquals(RoutingStrategyType.AUTO, cfg.getDefaultStrategy().type());
        assertTrue(cfg.getRules().isEmpty());
    }

    @Test
    void 旧7键双alias文件迁移() throws Exception {
        Files.writeString(file(), """
                enabled: true
                threshold: 0.5
                simpleAlias: "deepseek-chat"
                advancedAlias: "gpt-4o-mini"
                weights:
                  tokenLength: 0.4
                keywords: ["分析", "优化"]
                exemplars: ["请分析此架构"]
                """);
        RoutingConfig cfg = store.load(file());
        assertTrue(cfg.isEnabled());
        assertEquals(0.5, cfg.getThreshold());
        assertEquals("deepseek-chat", cfg.getLegacySimpleAlias());
        assertEquals("gpt-4o-mini", cfg.getLegacyAdvancedAlias());
        assertEquals(5, cfg.getTierNames().size(), "tierNames 缺失应归一化默认");
        assertEquals(RoutingStrategyType.AUTO, cfg.getDefaultStrategy().type(), "defaultStrategy 缺失应默认 AUTO 不限");
        assertTrue(cfg.getRules().isEmpty());
    }

    @Test
    void 规则与自定义层级往返() throws Exception {
        Map<Integer, String> names = Map.of(1, "Lite", 3, "Mid", 5, "Max");
        List<RoutingRule> rules = List.of(
                new RoutingRule(10, "admin", "os:admin_*", RoutingStrategy.fixed(5)),
                new RoutingRule(20, "vip", "im:dingtalk:vip_*", RoutingStrategy.range(3, 4)));
        RoutingConfig rc = new RoutingConfig(true, 0.6,
                RoutingConfig.defaults().getWeights(),
                List.of("分析"), List.of(),
                names, RoutingStrategy.auto(2), rules, "cheap", "premium");
        store.save(file(), rc);

        RoutingConfig loaded = store.load(file());
        assertEquals(3, loaded.getTierNames().size());
        assertEquals("Max", loaded.getTierNames().get(5));
        assertEquals(2, loaded.getRules().size());
        RoutingRule first = loaded.getRules().get(0);
        assertEquals("os:admin_*", first.identityPattern());
        assertEquals(RoutingStrategyType.FIXED, first.strategy().type());
        assertEquals(5, first.strategy().fixedTier());
        assertEquals(RoutingStrategyType.AUTO, loaded.getDefaultStrategy().type());
        assertEquals(2, loaded.getDefaultStrategy().minTier());
    }

    @Test
    void 损坏文件返回默认() throws Exception {
        Files.writeString(file(), "rules: [unclosed");
        assertEquals(RoutingConfig.defaults().getTierNames().size(), store.load(file()).getTierNames().size());
    }

    @Test
    void 身份通配匹配() {
        RoutingRule rule = new RoutingRule(1, "all im", "im:wechat:*", RoutingStrategy.auto(3));
        assertTrue(rule.matches("im:wechat:user123"));
        assertEquals(false, rule.matches("os:alice"));
        assertEquals(true, new RoutingRule(1, "all", "*", RoutingStrategy.auto(1)).matches("os:x"));
        assertEquals(false, new RoutingRule(1, "any", "os:*", RoutingStrategy.auto(1)).matches("im:x"));
    }
}
