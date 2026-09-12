package com.omniforge.gateway.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.ImInboundMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NapCat 回复发送器测试：/send_msg 载荷（群/私聊）、截断提示、retcode 错误。 */
class NapCatReplySenderTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> fixedResponse = new AtomicReference<>("{\"status\":\"ok\",\"retcode\":0}");
    private NapCatProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send_msg", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = fixedResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new NapCatProperties();
        properties.setNapcatHost("127.0.0.1");
        properties.setNapcatPort(server.getAddress().getPort());
        properties.setToken("tok-123");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 群聊回复带groupId与Bearer鉴权() throws Exception {
        NapCatReplySender sender = new NapCatReplySender(properties);
        ImInboundMessage original = new ImInboundMessage("qq", "m1", "u1", "hi", false, null,
                Map.of("messageType", "group", "groupId", "123456", "userId", "987"));

        sender.send(original, "你好，已收到");

        assertEquals("/send_msg", lastPath.get());
        assertEquals("Bearer tok-123", lastAuth.get());
        JsonNode payload = new ObjectMapper().readTree(lastBody.get());
        assertEquals("group", payload.path("message_type").asText());
        assertEquals("123456", payload.path("group_id").asText());
        assertEquals("你好，已收到", payload.path("message").get(0).path("data").path("text").asText());
    }

    @Test
    void 私聊回复带userId() throws Exception {
        NapCatReplySender sender = new NapCatReplySender(properties);
        ImInboundMessage original = new ImInboundMessage("qq", "m2", "u1", "hi", false, null,
                Map.of("messageType", "private", "userId", "555"));

        sender.send(original, "回复");

        JsonNode payload = new ObjectMapper().readTree(lastBody.get());
        assertEquals("private", payload.path("message_type").asText());
        assertEquals("555", payload.path("user_id").asText());
    }

    @Test
    void 超长文本截断并追加提示() throws Exception {
        NapCatReplySender sender = new NapCatReplySender(properties);
        ImInboundMessage original = new ImInboundMessage("qq", "m3", "u1", "hi", false, null,
                Map.of("messageType", "private", "userId", "555"));
        String longText = "长".repeat(1900);

        sender.send(original, longText);

        String sent = new ObjectMapper().readTree(lastBody.get())
                .path("message").get(0).path("data").path("text").asText();
        assertTrue(sent.length() <= 1800 + "（消息过长已截断）".length());
        assertTrue(sent.endsWith("（消息过长已截断）"), "截断需追加提示（补充建议 #3）");
    }

    @Test
    void 缺少回发目标报错() {
        NapCatReplySender sender = new NapCatReplySender(properties);
        ImInboundMessage original = new ImInboundMessage("qq", "m4", "u1", "hi", false, null,
                Map.of("messageType", "group"));
        assertThrows(IllegalStateException.class, () -> sender.send(original, "无目标"));
    }

    @Test
    void retcode非零视为失败() {
        fixedResponse.set("{\"status\":\"failed\",\"retcode\":100}");
        NapCatReplySender sender = new NapCatReplySender(properties);
        ImInboundMessage original = new ImInboundMessage("qq", "m5", "u1", "hi", false, null,
                Map.of("messageType", "private", "userId", "555"));
        assertThrows(IllegalStateException.class, () -> sender.send(original, "失败"));
    }
}
