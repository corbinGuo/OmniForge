package com.omniforge.core.gateway;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.omniforge.common.exception.ConfigurationException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Spring AI 官方实现，支持 dashscope 与 openai-compatible 两种提供商类型。
 *
 * <p>注意：本类是与 Spring AI / Spring AI Alibaba SDK 的【唯一】耦合点，
 * SDK 升级或 API 变更时仅需调整本文件。</p>
 */
public class SpringAiChatModelProvider implements ChatModelProvider {

    @Override
    public ChatModel create(ProviderConfig provider, String apiKey, String modelId) {
        String type = provider.getType();
        if (ProviderConfig.TYPE_DASHSCOPE.equals(type)) {
            return buildDashScope(apiKey, modelId);
        }
        if (ProviderConfig.TYPE_OPENAI_COMPATIBLE.equals(type)) {
            String baseUrl = provider.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new ConfigurationException(
                        "provider[" + provider.getName() + "] 为 openai-compatible 类型时必须配置 base-url");
            }
            return buildOpenAiCompatible(apiKey, baseUrl, modelId);
        }
        throw new ConfigurationException("不支持的提供商类型: " + type);
    }

    @Override
    public ChatOptions buildOptions(ProviderConfig provider, GatewayRequest request) {
        boolean hasTemperature = request.temperature() != null;
        boolean hasMaxTokens = request.maxTokens() != null;
        if (!hasTemperature && !hasMaxTokens) {
            return null;
        }
        if (ProviderConfig.TYPE_DASHSCOPE.equals(provider.getType())) {
            // SAA 1.1.x：builder() 返回 DashScopeChatOptionsBuilder（保留 with* 命名），
            // 不可声明为 DashScopeChatOptions.Builder（该嵌套类型已继承自 ToolCallingChatOptions）
            var builder = DashScopeChatOptions.builder();
            if (hasTemperature) {
                builder.withTemperature(request.temperature());
            }
            if (hasMaxTokens) {
                builder.withMaxToken(request.maxTokens());
            }
            return builder.build();
        }
        // Spring AI 1.1.x：with* 命名已改为无前缀（model/temperature/maxTokens）
        var builder = OpenAiChatOptions.builder();
        if (hasTemperature) {
            builder.temperature(request.temperature());
        }
        if (hasMaxTokens) {
            builder.maxTokens(request.maxTokens());
        }
        return builder.build();
    }

    private ChatModel buildDashScope(String apiKey, String modelId) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder().withModel(modelId).build())
                .build();
    }

    private ChatModel buildOpenAiCompatible(String apiKey, String baseUrl, String modelId) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(normalizeBaseUrl(baseUrl)).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelId).build())
                .build();
    }

    /**
     * 归一化 base-url：去掉尾部斜杠与 /v1 后缀。
     * Spring AI 的 OpenAiApi 会自动追加 /v1/chat/completions，
     * 用户配置若自带 /v1 会导致 404（https://host/v1/v1/chat/completions），此处兜底。
     */
    static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }
}
