package com.omniforge.gateway.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 企业微信接入配置（前缀 {@code omniforge.im.wecom}）。 */
@ConfigurationProperties(prefix = "omniforge.im.wecom")
public class WeComProperties {

    /** 接入开关（默认关闭） */
    private boolean enabled = false;

    /** 企业 ID */
    private String corpId;

    /** 应用 Secret */
    private String corpSecret;

    /** 应用 Agent ID */
    private Integer agentId;

    /** 回调 Token（URL 校验） */
    private String token;

    /** 回调 EncodingAESKey */
    private String aesKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getCorpSecret() {
        return corpSecret;
    }

    public void setCorpSecret(String corpSecret) {
        this.corpSecret = corpSecret;
    }

    public Integer getAgentId() {
        return agentId;
    }

    public void setAgentId(Integer agentId) {
        this.agentId = agentId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }
}
