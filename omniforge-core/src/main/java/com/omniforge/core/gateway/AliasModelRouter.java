package com.omniforge.core.gateway;

import com.omniforge.common.exception.ConfigurationException;

import java.util.Objects;

/** 默认路由：优先请求指定别名，否则使用网关默认模型（default-model）。 */
public class AliasModelRouter implements ModelRouter {

    @Override
    public String route(GatewayRequest request, ModelGatewayConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        String alias = request.alias() != null ? request.alias() : config.getDefaultModel();
        if (alias == null || alias.isBlank()) {
            throw new ConfigurationException("未指定模型别名，且 models.yml 未配置 default-model");
        }
        return alias;
    }
}
