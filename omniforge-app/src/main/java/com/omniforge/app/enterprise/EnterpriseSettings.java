package com.omniforge.app.enterprise;

/**
 * 企业版客户端设置（enterprise.yml，配置目录下，C-tier 批次 4-2）。
 *
 * @param serverUrl 企业版服务端地址（如 http://server:8080；空 = 未配置，登录框手输）
 */
public record EnterpriseSettings(String serverUrl) {

    public EnterpriseSettings {
        serverUrl = serverUrl == null ? "" : serverUrl.trim();
    }

    public static EnterpriseSettings defaults() {
        return new EnterpriseSettings("");
    }
}
