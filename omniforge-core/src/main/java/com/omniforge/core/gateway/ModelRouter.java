package com.omniforge.core.gateway;

/**
 * 模型路由 SPI。
 *
 * <p>社区版默认实现为别名直路由（{@link AliasModelRouter}）；
 * 专业版实现智能路由（按问题复杂度自动匹配最优性价比模型，需求 4.1 Pro 功能）。</p>
 */
public interface ModelRouter {

    /**
     * 决定本次请求使用的模型别名。
     *
     * @param request 原始请求（alias 可为 null）
     * @param config  当前生效的网关配置
     * @return 路由到的模型别名（必须存在于 config 中）
     */
    String route(GatewayRequest request, ModelGatewayConfig config);
}
