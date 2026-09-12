package com.omniforge.core.debate;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.omniforge.core.gateway.ModelGateway;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Objects;

/**
 * ReactAgent 封装器（Phase 2.1）：模型别名 → ChatModel → SAA ReactAgent。
 *
 * <p>每次辩论按用户所选模型动态创建（v5.2 冻结方案）；outputKey 必须全局唯一
 * （ParallelAgent.validateUniqueOutputKeys 强制），调用方按别名生成。</p>
 */
public class ReactAgentFactory {

    private final ModelGateway modelGateway;

    public ReactAgentFactory(ModelGateway modelGateway) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
    }

    /**
     * 创建辩论子 Agent。
     *
     * @param alias    模型别名（经网关解析出 ChatModel）
     * @param outputKey 该 Agent 在图状态中的输出键（全局唯一）
     */
    public ReactAgent createDebater(String alias, String outputKey) {
        ChatModel chatModel = modelGateway.chatModel(alias);
        return ReactAgent.builder()
                .name(alias)
                .description("多模型辩论辩手（模型：" + alias + "）")
                .model(chatModel)
                .outputKey(outputKey)
                .build();
    }

    /** 获取别名对应的底层 ChatModel（逐 token 流式执行器使用） */
    public ChatModel chatModel(String alias) {
        return modelGateway.chatModel(alias);
    }
}
