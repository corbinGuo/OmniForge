package com.omniforge.gateway;

/**
 * IM 消息处理器 SPI：消息通过路由校验后，在异步任务队列中回调。
 * Step 3/4 由 Agent 触发（AgentRunRequest/DebateRequest 以 IM 消息为输入）与
 * 回复格式化（AI 生成标识脚注）接入实现；Step 2 仅定义接口。
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * 处理一条已通过幂等与白名单校验的消息。
     * 抛出的异常由路由捕获并标记消息失败（允许重试）。
     */
    void handle(ImInboundMessage message) throws Exception;
}
