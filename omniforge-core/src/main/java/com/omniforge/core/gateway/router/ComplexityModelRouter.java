package com.omniforge.core.gateway.router;

import com.omniforge.core.gateway.AliasModelRouter;
import com.omniforge.core.gateway.GatewayRequest;
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
 * 智能路由（Pro 功能，C-tier 批次 3）：按问题复杂度自动选择最优性价比模型。
 *
 * <p>触发条件（设计确认 #4）：</p>
 * <ul>
 *   <li>请求显式指定 alias → 直通（手动选模型时路由不生效）；</li>
 *   <li>alias 为空且 License 为 Pro 且 routing.yml enabled → 智能路由；</li>
 *   <li>其余情况回退 {@link AliasModelRouter} 语义（default-model），与社区版一致。</li>
 * </ul>
 *
 * <p>复杂度分 = Σ(信号分 × 权重) / Σ(权重)，权重来自 routing.yml；
 * 分 ≥ threshold → advancedAlias（复杂 → 高级模型），否则 simpleAlias（简单 → 便宜模型）。
 * 配置热生效：每次路由前按文件 mtime 检测变化并重载。</p>
 */
public class ComplexityModelRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ComplexityModelRouter.class);

    private final LicenseService licenseService;
    private final RoutingSettingsStore store;
    private final Path settingsFile;
    private final List<RouteScorer> scorers;
    private final AliasModelRouter fallback = new AliasModelRouter();

    private volatile RoutingSettings cached = RoutingSettings.defaults();
    private volatile long cachedMtime = -1;

    public ComplexityModelRouter(LicenseService licenseService, RoutingSettingsStore store,
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
        // 用户手动指定模型 → 直通（确认 #4）
        if (request.alias() != null && !request.alias().isBlank()) {
            return request.alias();
        }
        RoutingSettings settings = settings();
        // CE 或路由关闭 → 社区版语义（default-model）
        if (!settings.isEnabled() || !licenseService.isPro()) {
            return fallback.route(request, config);
        }
        // 规则中配置的模型必须存在，否则回退（避免路由到不可用模型）
        if (config.findModel(settings.getSimpleAlias()) == null
                || config.findModel(settings.getAdvancedAlias()) == null) {
            log.warn("routing.yml 配置的模型别名不存在于 models.yml（simple={}，advanced={}），已回退默认模型",
                    settings.getSimpleAlias(), settings.getAdvancedAlias());
            return fallback.route(request, config);
        }
        double score = weightedScore(request.userText(), settings);
        String alias = score >= settings.getThreshold()
                ? settings.getAdvancedAlias() : settings.getSimpleAlias();
        log.debug("智能路由：复杂度分 {:.3f} → {}（阈值 {:.2f}）",
                score, alias, settings.getThreshold());
        return alias;
    }

    /** 加权求和；某信号权重为 0/缺失或打分异常 → 跳过该信号，权重重归一化 */
    private double weightedScore(String userText, RoutingSettings settings) {
        String text = userText == null ? "" : userText;
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
                signal = scorer.score(text, settings);
            } catch (RuntimeException e) {
                log.debug("复杂度信号 {} 异常（已跳过）：{}", scorer.name(), e.getMessage());
                continue;
            }
            total += Math.max(0.0, Math.min(1.0, signal)) * weight;
            weightSum += weight;
        }
        return weightSum <= 0 ? 0.0 : total / weightSum;
    }

    /** 读取路由规则（mtime 变化时热重载） */
    private RoutingSettings settings() {
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
