package com.omniforge.gateway.dingtalk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 钉钉接入配置（前缀 {@code omniforge.im.dingtalk}）。 */
@ConfigurationProperties(prefix = "omniforge.im.dingtalk")
public class DingTalkProperties {

    /** 接入开关（默认关闭） */
    private boolean enabled = false;

    /** 机器人签名密钥（安全设置-加签 页面获取；配置后回调签名校验生效） */
    private String secret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
