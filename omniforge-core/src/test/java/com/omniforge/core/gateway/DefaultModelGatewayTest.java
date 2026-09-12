package com.omniforge.core.gateway;

import com.omniforge.common.exception.ModelGatewayException;
import com.omniforge.common.exception.ModelTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultModelGatewayTest {

    @TempDir
    Path tempDir;

    private ChatModelProvider chatModelProvider;
    private DefaultModelGateway gateway;
    private ChatModel mockChatModel;

    @BeforeEach
    void setUp() throws Exception {
        Path configFile = tempDir.resolve("models.yml");
        Files.writeString(configFile, """
                default-model: test-model
                providers:
                  - name: mock-provider
                    type: dashscope
                    api-key: sk-test-key-1234567890
                models:
                  - alias: test-model
                    provider: mock-provider
                    model-id: test-model-1
                    timeout-seconds: 1
                  - alias: broken-model
                    provider: mock-provider
                    model-id: broken-1
                    timeout-seconds: 1
                """);

        mockChatModel = mock(ChatModel.class);
        when(mockChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))));

        chatModelProvider = mock(ChatModelProvider.class);
        when(chatModelProvider.create(any(), any(), any())).thenReturn(mockChatModel);
        when(chatModelProvider.buildOptions(any(), any())).thenReturn(null);

        gateway = new DefaultModelGateway(configFile, new ModelConfigLoader(), chatModelProvider,
                ref -> ref, new AliasModelRouter());
    }

    @AfterEach
    void tearDown() {
        gateway.close();
    }

    @Test
    void 按别名调用并返回统一响应() {
        GatewayChatResult result = gateway.chat(GatewayRequest.of("你好"));

        assertEquals("你好", result.text());
        assertEquals("test-model", result.alias());
        assertEquals("mock-provider", result.providerName());
        assertEquals("test-model-1", result.modelId());
        verify(mockChatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    void sdk直连chatModel调用计入用量与成本() {
        // SDK 直连路径（Agent/辩论/裁判/摘要经 chatModel(alias) 获取模型）必须记账，
        // 否则状态栏的累计调用次数与估算成本恒为 0（P0 修复）
        ChatModel mockWithUsage = mock(ChatModel.class);
        when(mockWithUsage.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(120, 30))
                        .build()));
        when(chatModelProvider.create(any(), any(), any())).thenReturn(mockWithUsage);
        gateway.reload();

        gateway.chatModel("test-model").call(new Prompt("hello"));

        GatewayUsage usage = gateway.usage();
        assertEquals(1, usage.totalCalls());
        assertEquals(120, usage.totalInputTokens());
        assertEquals(30, usage.totalOutputTokens());
    }

    @Test
    void 未指定别名时使用默认模型() {
        GatewayChatResult result = gateway.chat(GatewayRequest.of("hi"));
        assertEquals("test-model", result.alias());
    }

    @Test
    void 历史消息按system用户助手摘要顺序注入() {
        gateway.chat(new GatewayRequest(null, "系统提示", "当前问题", null, null,
                List.of(
                        new GatewayHistoryMessage(GatewayHistoryRole.USER, "历史问题"),
                        new GatewayHistoryMessage(GatewayHistoryRole.ASSISTANT, "历史回答"),
                        new GatewayHistoryMessage(GatewayHistoryRole.SYSTEM, "【对话历史摘要】旧内容摘要"))));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(mockChatModel, atLeastOnce()).call(captor.capture());
        List<Message> messages = captor.getValue().getInstructions();
        assertEquals(5, messages.size());
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        assertEquals("系统提示", messages.get(0).getText());
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
        assertEquals("历史问题", messages.get(1).getText());
        assertEquals(MessageType.ASSISTANT, messages.get(2).getMessageType());
        assertEquals(MessageType.SYSTEM, messages.get(3).getMessageType(), "摘要条目映射为 SYSTEM");
        assertEquals(MessageType.USER, messages.get(4).getMessageType());
        assertEquals("当前问题", messages.get(4).getText());
    }

    @Test
    void 单模型故障不影响其他模型() {
        ChatModel broken = mock(ChatModel.class);
        when(broken.call(any(Prompt.class))).thenThrow(new RuntimeException("network down"));
        when(chatModelProvider.create(any(), any(), eq("broken-1"))).thenReturn(broken);
        gateway.reload(); // 重新装配运行期状态，使新 stub 生效

        assertThrows(ModelGatewayException.class,
                () -> gateway.chat(new GatewayRequest("broken-model", null, "hi", null, null)));
        // 正常模型不受影响
        assertEquals("你好", gateway.chat(GatewayRequest.of("hi")).text());
    }

    @Test
    void 调用超时触发时间熔断() {
        ChatModel slow = mock(ChatModel.class);
        when(slow.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("slow"))));
        });
        when(chatModelProvider.create(any(), any(), any())).thenReturn(slow);
        gateway.reload(); // 重新装配运行期状态，使新 stub 生效

        assertThrows(ModelTimeoutException.class, () -> gateway.chat(GatewayRequest.of("hi")));
    }

    @Test
    void 热重载后新配置生效() throws Exception {
        Files.writeString(tempDir.resolve("models.yml"), """
                default-model: new-model
                providers:
                  - name: mock-provider
                    type: dashscope
                    api-key: sk-test-key-1234567890
                models:
                  - alias: new-model
                    provider: mock-provider
                    model-id: new-1
                """);

        gateway.reload();

        assertEquals("new-model", gateway.chat(GatewayRequest.of("hi")).alias());
        assertTrue(gateway.availableModels().stream().anyMatch(m -> m.alias().equals("new-model")));
    }

    @Test
    void 未知别名报错() {
        assertThrows(ModelGatewayException.class,
                () -> gateway.chat(new GatewayRequest("no-such-alias", null, "hi", null, null)));
    }

    @Test
    void 使用量计量累计() {
        gateway.chat(GatewayRequest.of("hi"));
        gateway.chat(GatewayRequest.of("hi"));

        GatewayUsage usage = gateway.usage();
        assertEquals(2, usage.totalCalls());
        assertTrue(usage.byAlias().containsKey("test-model"));
    }
}
