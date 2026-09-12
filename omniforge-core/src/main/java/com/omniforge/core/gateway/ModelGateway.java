package com.omniforge.core.gateway;

import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.Collection;

/**
 * 模型网关统一入口（需求 4.1）。
 *
 * <p>职责：
 * <ul>
 *   <li>统一适配 DashScope/OpenAI/DeepSeek/智谱AI 等多提供商（切换模型只需改配置）；</li>
 *   <li>配置热重载（models.yml 变更无需重启）；</li>
 *   <li>调用超时熔断（每模型独立超时）；</li>
 *   <li>单模型异常不影响其他模型；</li>
 *   <li>累计 Token 成本计量。</li>
 * </ul>
 */
public interface ModelGateway {

    /** 阻塞调用模型，返回统一响应 */
    GatewayChatResult chat(GatewayRequest request);

    /**
     * 流式调用模型，逐块返回文本（UI 流式渲染，Phase 2 #1 的基础）。
     * 订阅方取消订阅即中止；异常以 onError 信号终止。
     */
    Flux<String> streamText(GatewayRequest request);

    /** 当前可用模型列表（随热重载更新） */
    Collection<ModelInfo> availableModels();

    /** 累计使用量快照 */
    GatewayUsage usage();

    /**
     * 获取模型别名对应的底层 Spring AI ChatModel（实际实现通常为 ToolCallingChatModel）。
     * <p>仅供同模块引擎层（Agent/辩论引擎）直接使用；上层模块请勿依赖 Spring AI 类型。</p>
     */
    ChatModel chatModel(String alias);

    /** 解析别名：null/空白时返回默认模型别名（models.yml 的 default-model） */
    String resolveAlias(String alias);

    /**
     * 身份+文本感知的路由解析（Batch2：Agent/IM 等非 GatewayRequest 场景）。
     * <p>默认实现退回 {@link #resolveAlias}；{@link DefaultModelGateway} 覆盖为按
     * 身份策略/层级复杂度真正路由。alias 非空时恒直通。</p>
     *
     * @param identityKey 身份键（os:* / im:平台:发送者；null=默认策略）
     * @param userText    用户输入（复杂度评分的信号来源，可为空串）
     */
    default String routeAlias(String alias, String identityKey, String userText) {
        return resolveAlias(alias);
    }

    /** 重新加载 models.yml（文件监听与手动触发共用） */
    void reload();
}
