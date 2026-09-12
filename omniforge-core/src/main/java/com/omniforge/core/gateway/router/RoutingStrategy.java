package com.omniforge.core.gateway.router;

import java.util.List;
import java.util.Objects;

/**
 * 路由策略（Batch2：按层级）。
 *
 * @param type         策略类型（必填）
 * @param minTier      允许的最小层级（AUTO/RANGE 用；null 在 AUTO=不限）
 * @param maxTier      允许的最大层级（仅 RANGE 用）
 * @param fixedTier    强制层级（仅 FIXED 用）
 * @param excludeTiers 排除层级（仅 EXCLUDE 用）
 */
public record RoutingStrategy(RoutingStrategyType type, Integer minTier, Integer maxTier,
                              Integer fixedTier, List<Integer> excludeTiers) {

    public RoutingStrategy {
        type = Objects.requireNonNull(type, "type");
        excludeTiers = excludeTiers == null ? List.of() : List.copyOf(excludeTiers);
    }

    public static RoutingStrategy auto(Integer minTier) {
        return new RoutingStrategy(RoutingStrategyType.AUTO, minTier, null, null, List.of());
    }

    public static RoutingStrategy fixed(int tier) {
        return new RoutingStrategy(RoutingStrategyType.FIXED, null, null, tier, List.of());
    }

    public static RoutingStrategy range(int minTier, int maxTier) {
        return new RoutingStrategy(RoutingStrategyType.RANGE, minTier, maxTier, null, List.of());
    }

    public static RoutingStrategy exclude(List<Integer> tiers) {
        return new RoutingStrategy(RoutingStrategyType.EXCLUDE, null, null, null, tiers);
    }

    /** 某模型层级是否被本策略允许（null=未分级恒不允许，需显式分级才参与路由） */
    public boolean allows(Integer tier) {
        if (tier == null) {
            return false;
        }
        return switch (type) {
            case AUTO -> minTier == null || tier >= minTier;
            case FIXED -> Objects.equals(tier, fixedTier);
            case RANGE -> tier >= minTier && tier <= maxTier;
            case EXCLUDE -> !excludeTiers.contains(tier);
        };
    }
}
