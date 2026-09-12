package com.omniforge.gateway;

import com.omniforge.core.agent.AgentEngine;
import com.omniforge.core.agent.AgentRunResult;
import com.omniforge.core.agent.StopReason;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImAgentMessageHandlerTest {

    @Test
    void 普通消息经Agent执行并附加脚注回发() throws Exception {
        AgentEngine agentEngine = mock(AgentEngine.class);
        when(agentEngine.run(any())).thenReturn(new AgentRunResult("r1", "这是回复",
                List.of(), 1, 10, 20, StopReason.COMPLETED, 100, null));
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.resolveAlias(null)).thenReturn("test-model");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());
        ImReplySender sender = mock(ImReplySender.class);
        when(sender.platform()).thenReturn("dingtalk");

        ImAgentMessageHandler handler = new ImAgentMessageHandler(
                agentEngine, modelGateway, toolRegistry, null, null, List.of(sender), null);
        handler.handle(new ImInboundMessage("dingtalk", "m1", "u1", "你好", false, null,
                java.util.Map.of("sessionWebhook", "https://hook")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(ImInboundMessage.class), captor.capture());
        assertTrue(captor.getValue().contains("这是回复"));
        assertTrue(captor.getValue().contains("本条回复由 AI 自动生成"),
                "所有 IM 输出必须含 AI 生成标识脚注（合规底线）");
    }

    @Test
    void 辩论前缀消息走辩论引擎() throws Exception {
        AgentEngine agentEngine = mock(AgentEngine.class);
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.availableModels()).thenReturn(List.of(
                new ModelInfo("a", "p", "a", 1, 1, 120),
                new ModelInfo("b", "p", "b", 1, 1, 120)));
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());
        com.omniforge.core.debate.DebateEngine debateEngine = mock(com.omniforge.core.debate.DebateEngine.class);
        com.omniforge.core.debate.DebateResult result = new com.omniforge.core.debate.DebateResult(
                "s1", "主题", List.of("a", "b"), null, 3, "debate",
                List.of(new com.omniforge.core.debate.DebateRoundResult(1,
                        new java.util.LinkedHashMap<>(java.util.Map.of("a", "观点A", "b", "观点B")),
                        java.time.LocalDateTime.now())),
                List.of(), null, StopReason.MAX_ROUNDS, 100, null);
        when(debateEngine.stream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new com.omniforge.core.debate.DebateEvent.Completed(result)));
        ImReplySender sender = mock(ImReplySender.class);
        when(sender.platform()).thenReturn("dingtalk");

        ImAgentMessageHandler handler = new ImAgentMessageHandler(
                agentEngine, modelGateway, toolRegistry, debateEngine, null, List.of(sender), null);
        handler.handle(new ImInboundMessage("dingtalk", "m2", "u1", "辩论：AI 的未来", false, null,
                java.util.Map.of("sessionWebhook", "https://hook")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(ImInboundMessage.class), captor.capture());
        assertTrue(captor.getValue().contains("观点A"), "辩论摘要应含最后轮观点");
        assertTrue(captor.getValue().contains("MAX_ROUNDS"));
        // 辩论路径不应触发单模型 Agent
        org.mockito.Mockito.verify(agentEngine, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void 无回复通道时仅记录不抛出() throws Exception {
        AgentEngine agentEngine = mock(AgentEngine.class);
        when(agentEngine.run(any())).thenReturn(new AgentRunResult("r1", "回复",
                List.of(), 1, 1, 1, StopReason.COMPLETED, 1, null));
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.resolveAlias(null)).thenReturn("test-model");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());

        ImAgentMessageHandler handler = new ImAgentMessageHandler(
                agentEngine, modelGateway, toolRegistry, null, null, List.of(), null);
        handler.handle(new ImInboundMessage("unknown-platform", "m3", "u", "hi", false, null));
        // 不抛异常即通过
    }

    @Test
    void 会话上下文按键隔离且注入请求历史() throws Exception {
        AgentEngine agentEngine = mock(AgentEngine.class);
        when(agentEngine.run(any())).thenReturn(new AgentRunResult("r1", "回复",
                List.of(), 1, 1, 1, StopReason.COMPLETED, 1, null));
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.resolveAlias(null)).thenReturn("test-model");
        when(modelGateway.availableModels())
                .thenReturn(List.of(new ModelInfo("test-model", "mock", "m", 1, 1, 120)));
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());
        ImReplySender sender = mock(ImReplySender.class);
        when(sender.platform()).thenReturn("dingtalk");
        com.omniforge.core.context.ContextManager contextManager =
                new com.omniforge.core.context.DefaultContextManager(
                        new com.omniforge.core.context.ContextSettingsHolder(
                                com.omniforge.core.context.ContextSettings.defaults()),
                        null, 256, java.time.Duration.ofHours(24));

        ImAgentMessageHandler handler = new ImAgentMessageHandler(
                agentEngine, modelGateway, toolRegistry, null, null, List.of(sender), contextManager);
        handler.handle(new ImInboundMessage("dingtalk", "m1", "u1", "第一条", false, null,
                java.util.Map.of("sessionWebhook", "https://hook-a")));
        handler.handle(new ImInboundMessage("dingtalk", "m2", "u1", "别的会话", false, null,
                java.util.Map.of("sessionWebhook", "https://hook-b")));
        handler.handle(new ImInboundMessage("dingtalk", "m3", "u1", "第三条", false, null,
                java.util.Map.of("sessionWebhook", "https://hook-a")));

        ArgumentCaptor<com.omniforge.core.agent.AgentRunRequest> captor =
                ArgumentCaptor.forClass(com.omniforge.core.agent.AgentRunRequest.class);
        verify(agentEngine, org.mockito.Mockito.times(3)).run(captor.capture());
        var requests = captor.getAllValues();
        // 首次请求无历史
        assertEquals(0, requests.get(0).history().size());
        // 不同会话（hook-b）互不干扰：历史为空
        assertEquals(0, requests.get(1).history().size());
        // 同一会话（hook-a）第三次请求携带前一轮 user+assistant 两条历史
        assertEquals(2, requests.get(2).history().size());
        assertEquals("第一条", requests.get(2).history().get(0).getText());
        assertEquals("回复", requests.get(2).history().get(1).getText());
    }

    @Test
    void 请求携带IM身份键且首次走路由() throws Exception {
        AgentEngine agentEngine = mock(AgentEngine.class);
        when(agentEngine.run(any())).thenReturn(new AgentRunResult("r1", "回复",
                List.of(), 1, 1, 1, StopReason.COMPLETED, 1, null));
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.resolveAlias(null)).thenReturn("test-model");
        when(modelGateway.availableModels()).thenReturn(List.of());
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());
        ImReplySender sender = mock(ImReplySender.class);
        when(sender.platform()).thenReturn("dingtalk");

        ImAgentMessageHandler handler = new ImAgentMessageHandler(
                agentEngine, modelGateway, toolRegistry, null, null, List.of(sender), null);
        handler.handle(new ImInboundMessage("dingtalk", "m9", "u9", "你好", false, null,
                java.util.Map.of("sessionWebhook", "hook")));

        ArgumentCaptor<com.omniforge.core.agent.AgentRunRequest> captor =
                ArgumentCaptor.forClass(com.omniforge.core.agent.AgentRunRequest.class);
        verify(agentEngine).run(captor.capture());
        assertEquals("im:dingtalk:u9", captor.getValue().identityKey(),
                "路由身份键应为 im:平台:发送者");
        assertEquals(null, captor.getValue().alias(), "首次尝试应走路由（alias=null）");
    }
}
