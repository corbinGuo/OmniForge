package com.omniforge.core.gateway.router;

/**
 * 路由策略类型（Batch2：分层级权限路由）。
 *
 * <ul>
 *   <li>{@link #AUTO}   自动路由：允许 minTier 及以上（minTier=null 表示不限层级），按复杂度择低/高档；</li>
 *   <li>{@link #FIXED}  强制路由：固定使用某层级；</li>
 *   <li>{@link #RANGE}  范围路由：允许 minTier~maxTier 区间，按复杂度在区间两端择一；</li>
 *   <li>{@link #EXCLUDE} 排除路由：不允许使用指定层级。</li>
 * </ul>
 */
public enum RoutingStrategyType {
    AUTO,
    FIXED,
    RANGE,
    EXCLUDE
}
