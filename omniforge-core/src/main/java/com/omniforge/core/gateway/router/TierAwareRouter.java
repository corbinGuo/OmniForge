package com.omniforge.core.gateway.router;

import com.omniforge.core.gateway.AliasModelRouter;
import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.ModelConfig;
import com.omniforge.core.gateway.ModelGatewayConfig;
import com.omniforge.core.gateway.ModelRouter;
import com.omniforge.core.persistence.service.LicenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分层级智能路由（Batch2，替换双 alias 装配）：
 * <ol>
 *   <li>请求显式指定 alias → 直通；</li>
 *   <li>非 Pro 或 routing.yml disabled → AliasModelRouter（default-model）；</li>
 *   <li>按身份键（request.identityKey()）匹配规则（priority 升序，glob），未命中 → defaultStrategy；</li>
 *   <li>由策略算出允许层级集合，候选 = 已分级且层级允许的模型；</li>
 *   <li>候选空：若全库均未分级且 legacy 双 alias 可用 → 旧行为；否则回退默认模型并告警；</li>
 *   <li>候选非空：低档锚=允许层级最低、高档锚=最高；复杂度分≥threshold 取高档否则低档（同层多模型取配置顺序首个）。</li>
 * </ol>
 * 配置热生效：每次路由按文件 mtime 重载（同旧实现）。
 */
public class TierAwareRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(TierAwareRouter.class);

    private final LicenseService licenseService;
    private final RoutingConfigStore store;
    private final Path settingsFile;
    private final List<RouteScorer> scorers;
    private final AliasModelRouter fallback = new AliasModelRouter();

    private volatile RoutingConfig cached = RoutingConfig.defaults();
    private volatile long cachedMtime = -1;

    public TierAwareRouter(LicenseService licenseService, RoutingConfigStore store,
                           Path settingsFile, List<RouteScorer> scorers) {
        this.licenseService = Objects.requireNonNull(licenseService, "licenseService");
        this.store = Objects.requireNonNull(store, "store");
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
        this.scorers = List.copyOf(scorers);
    }

    @Override
    public String route(GatewayRequest request, ModelGatewayConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        if (request.alias() != null && !request.alias().isBlank()) {
            return request.alias();
        }
        RoutingConfig settings = settings();
        if (!settings.isEnabled() || !licenseService.isPro()) {
            return fallback.route(request, config);
        }
        RoutingStrategy strategy = resolveStrategy(settings, request.identityKey());
        List<ModelConfig> candidates = config.getModels().stream()
                .filter(m -> strategy.allows(m.getTier()))
                .toList();
        if (candidates.isEmpty()) {
            // 全库未分级 → legacy 双 alias 兜底；否则回退默认并告警
            if (config.getModels().stream().allMatch(m -> m.getTier() == null)
                    && legacyAliasesUsable(settings, config)) {
                double score = weightedScore(request.userText(), settings);
                String alias = score >= settings.getThreshold()
                        ? settings.getLegacyAdvancedAlias() : settings.getLegacySimpleAlias();
                log.debug("分层路由(legacy)：复杂度分 {:.3f} → {}", score, alias);
                return alias;
            }
            log.warn("路由策略无可用模型（strategy={}），已回退默认模型", strategy);
            return fallback.route(request, config);
        }
        int lowTier = Integer.MAX_VALUE;
        int highTier = 0;
        for (ModelConfig model : candidates) {
            int tier = model.getTier();
            lowTier = Math.min(lowTier, tier);
            highTier = Math.max(highTier, tier);
        }
        double score = weightedScore(request.userText(), settings);
        int target = score >= settings.getThreshold() ? highTier : lowTier;
        for (ModelConfig model : candidates) {
            if (model.getTier() == target) {
                log.debug("分层路由：复杂度分 {:.3f} → tier{} [{}]（阈值 {:.2f}）",
                        score, target, model.getAlias(), settings.getThreshold());
                return model.getAlias();
            }
        }
        return candidates.get(0).getAlias(); // 兜底（理论上不可达）
    }

    /** 规则 priority 升序首个命中，否则默认策略 */
    private RoutingStrategy resolveStrategy(RoutingConfig settings, String identityKey) {
        RoutingRule matched = null;
        for (RoutingRule rule : settings.getRules()) {
            if (matched == null || rule.priority() < matched.priority()) {
                if (rule.matches(identityKey)) {
                    matched = rule;
                }
            }
        }
        return matched == null ? settings.getDefaultStrategy() : matched.strategy();
    }

    private boolean legacyAliasesUsable(RoutingConfig settings, ModelGatewayConfig config) {
        String simple = settings.getLegacySimpleAlias();
        String advanced = settings.getLegacyAdvancedAlias();
        return simple != null && !simple.isBlank()
                && advanced != null && !advanced.isBlank()
                && config.findModel(simple) != null && config.findModel(advanced) != null;
    }

    /** 加权求和；权重缺失/≤0 或信号异常 → 跳过该信号并重归一化 */
    private double weightedScore(String userText, RoutingConfig settings) {
        String text = userText == null ? "" : userText;
        // 信号实现共用旧 RoutingSettings 视图（含 keywords/exemplars/weights）
        RoutingSettings scoreView = new RoutingSettings(
                settings.isEnabled(),
                settings.getLegacySimpleAlias(),
                settings.getLegacyAdvancedAlias(),
                settings.getThreshold(),
                settings.getWeights(),
                settings.getKeywords(),
                settings.getExemplars());
        Map<String, Double> weights = settings.getWeights();
        double total = 0;
        double weightSum = 0;
        for (RouteScorer scorer : scorers) {
            Double weight = weights.get(scorer.name());
            if (weight == null || weight <= 0) {
                continue;
            }
            double signal;
            try {
                signal = scorer.score(text, scoreView);
            } catch (RuntimeException e) {
                log.debug("复杂度信号 {} 异常（已跳过）：{}", scorer.name(), e.getMessage());
                continue;
            }
            total += Math.max(0.0, Math.min(1.0, signal)) * weight;
            weightSum += weight;
        }
        return weightSum <= 0 ? 0.0 : total / weightSum;
    }

    /** 读取配置（mtime 变化热重载） */
    private RoutingConfig settings() {
        try {
            if (!Files.exists(settingsFile)) {
                cached = store.load(settingsFile); // 首次加载：写入默认模板
                cachedMtime = -1;
                return cached;
            }
            long mtime = Files.getLastModifiedTime(settingsFile).toMillis();
            if (mtime != cachedMtime) {
                cachedMtime = mtime;
                cached = store.load(settingsFile);
            }
            return cached;
        } catch (IOException e) {
            log.warn("routing.yml 读取失败（使用上一次配置）：{}", e.getMessage());
            return cached;
        }
    }
}
