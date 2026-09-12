package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理 API 客户端测试（P2-2，JDK HttpServer 桩服务，零新依赖）：
 * 各端点往返解析 + 服务端错误 message 透出 + 401 → AuthExpired。
 */
class EnterpriseAdminClientTest {

    @Test
    void 部门用户角色能力端点往返解析() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"admin\",\"username\":\"admin\"}"));
        server.createContext("/api/admin/departments", exchange -> {
            String method = exchange.getRequestMethod();
            String uri = exchange.getRequestURI().toString();
            if (method.equals("GET")) {
                respond(exchange, "[{\"id\":\"d-1\",\"name\":\"研发部\",\"memberCount\":3}]");
            } else if (method.equals("POST")) {
                respond(exchange, "{\"id\":\"d-2\",\"name\":\"测试部\",\"memberCount\":0}");
            } else if (method.equals("PUT") && uri.startsWith("/api/admin/departments/d-2")) {
                respond(exchange, "{\"id\":\"d-2\",\"name\":\"测试中心\",\"memberCount\":0}");
            } else {
                error(exchange, 409, "部门已存在");
            }
        });
        server.createContext("/api/admin/users", exchange -> {
            String uri = exchange.getRequestURI().toString();
            if (exchange.getRequestMethod().equals("GET")) {
                if (!uri.contains("keyword=dev") || !uri.contains("page=1")) {
                    error(exchange, 400, "查询参数缺失");
                    return;
                }
                respond(exchange, "{\"items\":[{\"id\":\"u-1\",\"username\":\"alice\",\"name\":\"爱丽丝\","
                        + "\"mobile\":\"138\",\"departmentId\":\"d-1\",\"departmentName\":\"研发部\","
                        + "\"role\":\"member\",\"enabled\":true,\"createdAt\":\"2026-09-05T10:00:00\"}],"
                        + "\"total\":1,\"page\":1,\"size\":20}");
            } else if (exchange.getRequestMethod().equals("POST")) {
                respond(exchange, "{\"id\":\"u-2\",\"username\":\"bob\",\"name\":null,\"mobile\":null,"
                        + "\"departmentId\":null,\"departmentName\":null,\"role\":\"member\","
                        + "\"enabled\":true,\"createdAt\":\"2026-09-05T10:00:00\"}");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/admin/users/u-1", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                respond(exchange, "");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/admin/roles", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, "[{\"id\":\"r-1\",\"name\":\"admin\",\"builtIn\":true,\"permissions\":[]},"
                        + "{\"id\":\"r-2\",\"name\":\"运营\",\"builtIn\":false,\"permissions\":[\"audit\"]}]");
            } else if (exchange.getRequestMethod().equals("POST")) {
                respond(exchange, "{\"id\":\"r-3\",\"name\":\"客服\",\"builtIn\":false,"
                        + "\"permissions\":[\"session\",\"chat\"]}");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/admin/roles/r-1", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                error(exchange, 409, "内置角色不可删除");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/admin/roles/r-2", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                respond(exchange, "{\"id\":\"r-2\",\"name\":\"运营\",\"builtIn\":false,"
                        + "\"permissions\":[\"audit\"]}");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/admin/capabilities", exchange ->
                respond(exchange, "[{\"id\":\"audit\",\"name\":\"审计日志\"},"
                        + "{\"id\":\"session\",\"name\":\"会话管理\"}]"));
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("admin", "pw");
            assertEquals("admin", client.login("admin", "pw").role());
            client.logout();
            client.login("admin", "pw");

            // 部门
            assertEquals("研发部", client.listDepartments(null).get(0).name());
            assertEquals(3, client.listDepartments(null).get(0).memberCount());
            assertEquals("测试部", client.createDepartment("测试部").name());
            assertEquals("测试中心", client.renameDepartment("d-2", "测试中心").name());

            // 用户（分页 + 过滤参数透传）
            var page = client.listUsers("dev", null, null, null, 1, 20);
            assertEquals(1, page.items().size());
            assertEquals("爱丽丝", page.items().get(0).name());
            assertEquals("研发部", page.items().get(0).departmentName());
            assertEquals(1, page.total());
            assertEquals("bob", client.createUser(new EnterpriseApiClient.UserCreate(
                    "bob", null, null, null, "member", "bob123456")).username());
            client.deleteUser("u-1"); // 不抛即成功

            // 角色 + 能力
            var roles = client.listRoles();
            assertEquals(2, roles.size());
            assertTrue(roles.get(0).builtIn());
            assertEquals("客服", client.createRole(new EnterpriseApiClient.RoleSave(
                    "客服", java.util.List.of("session", "chat"))).name());
            assertEquals(2, client.capabilities().size());
            // 自定义角色更新（桩：PUT /roles/r-2 未注册 → 走 409 断言下一条）
            EnterpriseApiClient.RoleDto updated = client.updateRole("r-2",
                    new EnterpriseApiClient.RoleSave("运营", java.util.List.of("audit")));
            assertEquals("运营", updated.name());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 错误体中文message透出且401转AuthExpired() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"admin\",\"username\":\"admin\"}"));
        server.createContext("/api/admin/roles/r-1", exchange ->
                error(exchange, 409, "内置角色不可删除"));
        server.createContext("/api/admin/departments", exchange ->
                error(exchange, 401, "登录已过期"));
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("admin", "pw");
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> client.deleteRole("r-1"));
            assertTrue(conflict.getMessage().contains("内置角色不可删除"),
                    "应携带服务端中文 message，实际：" + conflict.getMessage());
            assertThrows(EnterpriseApiClient.EnterpriseAuthException.class,
                    () -> client.createDepartment("x"));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void error(com.sun.net.httpserver.HttpExchange exchange, int status, String message)
            throws java.io.IOException {
        byte[] bytes = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
