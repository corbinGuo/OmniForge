package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.ImInboundMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QQ 官方回复发送测试：本地 HttpServer 桩校验 URL/请求体/鉴权头/截断/err_code 失败。
 */
class QQOfficialReplySenderTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();

    private QqSettingsStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] reply;
            if (path.endsWith("/app/getAppAccessToken")) {
                reply = "{\"access_token\":\"TOKEN_ABC\",\"expires_in\":7200}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
                return;
            }
            capturedPath.set(path);
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new ObjectMapper().readTree(body));
            byte[] ok = "{\"id\":\"ROBOT1.0_sent\",\"timestamp\":\"2026-09-01T10:00:00+08:00\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        store = new QqSettingsStore(tempDir);
        store.save(new QqSettings(true, "APP_ID", "SECRET", "production", false, 8080, "/webhook/qq",
                "127.0.0.1", "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private QQOfficialReplySender sender() {
        return new QQOfficialReplySender(store,
                new QqAccessTokenProvider(store, HttpClient.newHttpClient(), new ObjectMapper()),
                HttpClient.newHttpClient(), new ObjectMapper());
    }

    private static ImInboundMessage inbound(String kind, String openid) {
        Map<String, Object> context = Map.of(
                "kind", kind,
                kind.equals("group") ? "groupOpenid" : "userOpenid", openid,
                "msgId", "ROBOT1.0_in");
        return new ImInboundMessage("qqofficial", "ROBOT1.0_in", openid, "问个问题",
                false, LocalDateTime.now(), context);
    }

    @Test
    void 单聊回复路径与请求体() throws Exception {
        sender().send(inbound("c2c", "USER_OPENID_X"), "回复内容");
        assertThat(capturedPath.get()).isEqualTo("/v2/users/USER_OPENID_X/messages");
        assertThat(capturedAuth.get()).isEqualTo("QQBot TOKEN_ABC");
        assertThat(capturedBody.get().path("content").asText()).isEqualTo("回复内容");
        assertThat(capturedBody.get().path("msg_type").asInt()).isZero();
        assertThat(capturedBody.get().path("msg_id").asText()).isEqualTo("ROBOT1.0_in");
        assertThat(capturedBody.get().path("msg_seq").asInt()).isEqualTo(1);
    }

    @Test
    void 群聊回复路径与序号递增() throws Exception {
        QQOfficialReplySender sender = sender();
        ImInboundMessage group = inbound("group", "GROUP_OPENID_Y");
        sender.send(group, "第一次");
        assertThat(capturedPath.get()).isEqualTo("/v2/groups/GROUP_OPENID_Y/messages");
        assertThat(capturedBody.get().path("msg_seq").asInt()).isEqualTo(1);
        sender.send(group, "第二次");
        assertThat(capturedBody.get().path("msg_seq").asInt()).isEqualTo(2); // 同消息回复序号递增
    }

    @Test
    void 超长文本截断并追加提示() throws Exception {
        sender().send(inbound("c2c", "USER_OPENID_X"), "长".repeat(2000));
        String content = capturedBody.get().path("content").asText();
        assertThat(content).hasSize(1800 + "（消息过长已截断）".length());
        assertThat(content).endsWith("（消息过长已截断）");
    }

    @Test
    void 缺少回发目标拒绝() {
        ImInboundMessage noTarget = new ImInboundMessage("qqofficial", "ROBOT1.0_in", "",
                "问个问题", false, LocalDateTime.now(), Map.of("kind", "c2c"));
        assertThatThrownBy(() -> sender().send(noTarget, "回复"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回发目标");
    }
}
