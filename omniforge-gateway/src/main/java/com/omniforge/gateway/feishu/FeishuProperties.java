package com.omniforge.gateway.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 飞书接入配置（前缀 {@code omniforge.im.feishu}）。 */
@ConfigurationProperties(prefix = "omniforge.im.feishu")
public class FeishuProperties {

    /** 接入开关（默认关闭） */
    private boolean enabled = false;

    /** 应用 App ID */
    private String appId;

    /** 应用 App Secret */
    private String appSecret;

    /** 事件订阅 Verification Token */
    private String verificationToken;

    /** 事件订阅 Encrypt Key（未配置加密时留空） */
    private String encryptKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }
}
