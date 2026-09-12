package com.omniforge.core.gateway;

/**
 * API Key 引用解析器。
 *
 * <p>models.yml 中 api-key 字段支持三种写法：
 * {@code ${ENV_VAR}}（环境变量）、{@code keystore:<id>}（JCE 加密存储）、明文（不推荐）。
 * 实现需保证解析结果不进入日志（需求 9.2 安全底线）。</p>
 */
public interface ApiKeyResolver {

    /** 解析引用，返回真实 API Key；无法解析时抛 {@link com.omniforge.common.exception.ConfigurationException} */
    String resolve(String reference);
}
