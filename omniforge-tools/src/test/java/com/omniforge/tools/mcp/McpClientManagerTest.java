package com.omniforge.tools.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpClientManagerTest {

    /** 假客户端：暴露一个 echo 工具 */
    private static McpSyncClient fakeClient() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        McpSchema.Tool tool = new McpSchema.Tool("echo", null, "回显", schema, null, null, null);
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        return client;
    }

    private static McpSettings settingsOf(McpServerConfig config) {
        return new McpSettings(List.of(config));
    }

    /** 轮询等待条件成立（连接为异步虚拟线程） */
    private static void awaitUntil(java.util.function.BooleanSupplier condition, int timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("超时未满足条件");
    }

    @Test
    void 禁用服务器不建立连接() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        McpClientManager manager = new McpClientManager(
                new McpSettingsHolder(settingsOf(McpServerConfig.stdio("s1", "npx", List.of(), false))),
                config -> {
                    calls.incrementAndGet();
                    return fakeClient();
                }, 10);
        manager.start();
        Thread.sleep(200);
        assertEquals(0, calls.get(), "禁用服务器不应连接");
        assertTrue(manager.tools().isEmpty());
        manager.stop();
    }

    @Test
    void 连接失败后指数退避重试成功() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        McpClientManager manager = new McpClientManager(
                new McpSettingsHolder(settingsOf(McpServerConfig.stdio("s1", "npx", List.of(), true))),
                config -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("服务未就绪");
                    }
                    return fakeClient();
                }, 10); // 退避基础 10ms → 首次失败后 20ms 重试
        manager.start();
        awaitUntil(() -> calls.get() >= 2, 5_000);
        awaitUntil(() -> manager.tools().size() == 1, 5_000);
        assertEquals("mcp_s1_echo", manager.tools().get(0).spec().name());
        manager.stop();
    }

    @Test
    void 设置变更移除服务器并关闭客户端() throws Exception {
        McpSyncClient client = fakeClient();
        McpSettingsHolder holder = new McpSettingsHolder(
                settingsOf(McpServerConfig.stdio("s1", "npx", List.of(), true)));
        McpClientManager manager = new McpClientManager(holder, config -> client, 10);
        manager.start();
        awaitUntil(() -> manager.tools().size() == 1, 5_000);

        holder.update(McpSettings.empty());
        manager.apply(holder.current());
        assertTrue(manager.tools().isEmpty(), "移除后无工具");
        verify(client).closeGracefully();
        manager.stop();
    }

    @Test
    void 工具变化监听器被通知() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        McpClientManager manager = new McpClientManager(
                new McpSettingsHolder(settingsOf(McpServerConfig.stdio("s1", "npx", List.of(), true))),
                config -> fakeClient(), 10);
        manager.setChangeListener(tools -> notifications.incrementAndGet());
        manager.start();
        awaitUntil(() -> notifications.get() >= 1, 5_000);
        manager.stop();
    }
}
