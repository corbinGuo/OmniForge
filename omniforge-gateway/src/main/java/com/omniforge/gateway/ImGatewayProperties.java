package com.omniforge.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * IM 网关配置（前缀 {@code omniforge.im}）。
 *
 * <p>白名单默认关闭（放行全部）；开启后未配置任何发送者则拒绝全部（安全底线）。</p>
 */
@ConfigurationProperties(prefix = "omniforge.im")
public class ImGatewayProperties {

    /** 白名单开关（默认关闭） */
    private boolean whitelistEnabled = false;

    /** 白名单发送者（支持精确匹配与前缀通配 "admin_*"，"*" 放行全部） */
    private List<String> whitelistSenders = new ArrayList<>();

    public boolean isWhitelistEnabled() {
        return whitelistEnabled;
    }

    public void setWhitelistEnabled(boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
    }

    public List<String> getWhitelistSenders() {
        return whitelistSenders;
    }

    public void setWhitelistSenders(List<String> whitelistSenders) {
        this.whitelistSenders = whitelistSenders != null ? whitelistSenders : new ArrayList<>();
    }
}
