package com.omniforge.core.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 单个模型条目：别名 → 提供商 + 模型 ID + 定价 + 超时 + 上下文窗口。 */
public class ModelConfig {

    private String alias;

    private String provider;

    @JsonProperty("model-id")
    private String modelId;

    @JsonProperty("input-price-per-1m")
    private double inputPricePer1m;

    @JsonProperty("output-price-per-1m")
    private double outputPricePer1m;

    @JsonProperty("timeout-seconds")
    private int timeoutSeconds = 120;

    /** 模型上下文窗口 token（null = 未知，上下文裁剪仅按全局预算） */
    @JsonProperty("context-window-tokens")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer contextWindowTokens;

    /** 模型层级 1~5（null = 未分级；Batch2 分层级路由用），越界写回 null */
    @JsonProperty("tier")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer tier;

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public double getInputPricePer1m() {
        return inputPricePer1m;
    }

    public void setInputPricePer1m(double inputPricePer1m) {
        this.inputPricePer1m = inputPricePer1m;
    }

    public double getOutputPricePer1m() {
        return outputPricePer1m;
    }

    public void setOutputPricePer1m(double outputPricePer1m) {
        this.outputPricePer1m = outputPricePer1m;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(Integer contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    /** 模型层级：1~5；null/越界一律归一为 null（未分级） */
    public Integer getTier() {
        return tier;
    }

    public void setTier(Integer tier) {
        this.tier = (tier != null && tier >= 1 && tier <= 5) ? tier : null;
    }
}
