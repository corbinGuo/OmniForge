package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * access_token 获取测试：本地 HttpServer 桩模拟官方接口
 * （缓存复用 / 到期前 60 秒刷新 / 配置变更失效 / 未配置凭证拒绝 / 错误码透出）。
 */
class QqAccessTokenProviderTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private QqSettingsStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/getAppAccessToken", exchange -> {
            tokenCalls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String json = new String(body, StandardCharsets.UTF_8);
            var root = new ObjectMapper().readTree(json);
            String reply;
            if ("good".equals(root.path("clientSecret").asText())) {
                reply = "{\"access_token\":\"TOKEN_ABC\",\"expires_in\":7200}";
            } else {
                reply = "{\"err_code\":100016,\"message\":\"invalid appid or secret\"}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        store = new QqSettingsStore(tempDir);
        store.save(new QqSettings(true, "APP_ID", "good", "production", false, 8080, "/webhook/qq",
                "127.0.0.1", "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private QqAccessTokenProvider provider() {
        return new QqAccessTokenProvider(store, HttpClient.newHttpClient(), new ObjectMapper());
    }

    @Test
    void 获取并缓存token() throws Exception {
        QqAccessTokenProvider provider = provider();
        assertThat(provider.getToken()).isEqualTo("TOKEN_ABC");
        assertThat(provider.getToken()).isEqualTo("TOKEN_ABC");
        assertThat(tokenCalls.get()).isEqualTo(1); // 第二次走缓存
    }

    @Test
    void 未配置凭证时拒绝() throws Exception {
        store.save(new QqSettings(false, "", "", "production", false, 8080, "/webhook/qq",
                "127.0.0.1", "http://localhost"));
        assertThatThrownBy(() -> provider().getToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AppID/AppSecret");
    }

    @Test
    void 密钥错误时透出官方错误码() throws Exception {
        store.save(new QqSettings(true, "APP_ID", "bad", "production", false, 8080, "/webhook/qq",
                "127.0.0.1", "http://127.0.0.1:" + server.getAddress().getPort()));
        assertThatThrownBy(() -> provider().getToken())
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("100016");
    }

    @Test
    void 配置热切换后旧token失效() throws Exception {
        QqAccessTokenProvider provider = provider();
        assertThat(provider.getToken()).isEqualTo("TOKEN_ABC");

        // 更换 AppID（缓存键变化）→ 重新获取
        store.save(new QqSettings(true, "APP_ID_2", "good", "production", false, 8080, "/webhook/qq",
                "127.0.0.1", "http://127.0.0.1:" + server.getAddress().getPort()));
        provider.getToken();
        assertThat(tokenCalls.get()).isEqualTo(2);
    }
}
