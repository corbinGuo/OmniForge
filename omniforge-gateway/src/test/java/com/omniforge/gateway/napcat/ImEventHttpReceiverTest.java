package com.omniforge.gateway.napcat;

import com.omniforge.gateway.ImMessageRouter;
import com.omniforge.gateway.ImInboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 事件接收器端到端测试：上报 → 路由；token 拒绝；端口冲突优雅降级。 */
class ImEventHttpReceiverTest {

    private static final String GROUP_EVENT = """
            {"post_type":"message","message_type":"group","message_id":-1,
             "self_id":"10001","user_id":"987","group_id":"123",
             "message":[{"type":"text","data":{"text":"你好"}}],"raw_message":"你好"}
            """;

    private ImEventHttpReceiver receiver;

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void 事件上报进入路由且错误token被拒绝() throws Exception {
        NapCatProperties properties = new NapCatProperties();
        properties.setEnabled(true);
        properties.setToken("tok-1");
        properties.setEventPort(freePort());

        ImMessageRouter router = mock(ImMessageRouter.class);
        ObjectProvider<ImMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(router);
        receiver = new ImEventHttpReceiver(properties, new NapCatAdapter(properties), provider);
        receiver.start();
        assertEquals(true, receiver.isRunning());

        HttpClient client = HttpClient.newHttpClient();
        // 正确 token → 200，路由被调用
        HttpResponse<String> ok = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + properties.getEventPort() + "/onebot/event"))
                .header("Authorization", "Bearer tok-1")
                .POST(HttpRequest.BodyPublishers.ofString(GROUP_EVENT, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        verify(router).route(any(ImInboundMessage.class));

        // 错误 token → 400，路由仍只被调用了最初那一次
        HttpResponse<String> denied = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + properties.getEventPort() + "/onebot/event"))
                .POST(HttpRequest.BodyPublishers.ofString(GROUP_EVENT, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, denied.statusCode());
        verify(router, org.mockito.Mockito.times(1)).route(any());
    }

    @Test
    void 端口被占用时优雅降级不启动() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0)) {
            NapCatProperties properties = new NapCatProperties();
            properties.setEnabled(true);
            properties.setToken("tok-2");
            properties.setEventPort(occupied.getLocalPort());
            receiver = new ImEventHttpReceiver(properties, new NapCatAdapter(properties),
                    mock(ObjectProvider.class));
            receiver.start();
            assertFalse(receiver.isRunning(), "端口冲突应优雅降级（补充建议 #2）");
        }
    }

    @Test
    void 未配置token拒绝启动() {
        NapCatProperties properties = new NapCatProperties();
        properties.setEnabled(true);
        receiver = new ImEventHttpReceiver(properties, new NapCatAdapter(properties),
                mock(ObjectProvider.class));
        receiver.start();
        assertFalse(receiver.isRunning(), "无 token 拒绝裸奔");
    }

    @Test
    void 心跳事件被忽略且不触发路由() throws Exception {
        NapCatProperties properties = new NapCatProperties();
        properties.setEnabled(true);
        properties.setToken("tok-3");
        properties.setEventPort(freePort());

        ImMessageRouter router = mock(ImMessageRouter.class);
        ObjectProvider<ImMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(router);
        receiver = new ImEventHttpReceiver(properties, new NapCatAdapter(properties), provider);
        receiver.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + properties.getEventPort() + "/onebot/event"))
                .header("Authorization", "Bearer tok-3")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"post_type\":\"meta_event\",\"meta_event_type\":\"heartbeat\"}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ignored", response.body());
        verify(router, never()).route(any());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
