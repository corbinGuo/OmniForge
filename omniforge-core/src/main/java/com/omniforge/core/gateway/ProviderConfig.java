package com.omniforge.core.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型提供商连接配置。
 *
 * <p>type 取值：
 * <ul>
 *   <li>{@link #TYPE_DASHSCOPE} —— 阿里云百炼（DashScope）</li>
 *   <li>{@link #TYPE_OPENAI_COMPATIBLE} —— OpenAI 兼容接口（OpenAI/DeepSeek/智谱AI 等，需 base-url）</li>
 * </ul>
 */
public class ProviderConfig {

    public static final String TYPE_DASHSCOPE = "dashscope";
    public static final String TYPE_OPENAI_COMPATIBLE = "openai-compatible";

    private String name;

    private String type;

    /** API Key 引用：${ENV} / keystore:&lt;id&gt; / 明文，见 {@link ApiKeyResolver} */
    @JsonProperty("api-key")
    private String apiKey;

    @JsonProperty("base-url")
    private String baseUrl;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
