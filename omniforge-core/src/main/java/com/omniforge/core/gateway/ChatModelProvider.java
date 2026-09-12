package com.omniforge.core.gateway;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * ChatModel 工厂 SPI：按提供商配置构建底层 Spring AI ChatModel（测试中可 mock）。
 *
 * <p>每个模型别名对应一个 ChatModel 实例（options 在构建期绑定 model-id），
 * 由 {@link DefaultModelGateway} 在热重载时重建。</p>
 */
public interface ChatModelProvider {

    /**
     * 构建 ChatModel。
     *
     * @param provider 提供商配置
     * @param apiKey   已解析的真实 API Key（由 {@link ApiKeyResolver} 提供）
     * @param modelId  提供方模型 ID
     */
    ChatModel create(ProviderConfig provider, String apiKey, String modelId);

    /**
     * 构建请求级选项（temperature/maxTokens 覆盖）。
     * 请求无覆盖项时返回 null，网关将不携带 options 调用模型。
     */
    ChatOptions buildOptions(ProviderConfig provider, GatewayRequest request);
}
