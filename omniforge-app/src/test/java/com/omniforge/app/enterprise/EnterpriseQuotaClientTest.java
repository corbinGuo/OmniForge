package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 企业用量/配额客户端测试（ENTERPRISE_METERING rev2：企业/部门/用户层级）。 */
class EnterpriseQuotaClientTest {

    @Test
    void 用量汇总与限额解析() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"admin\",\"username\":\"a\"}"));
        server.createContext("/api/admin/quota/summary", exchange -> respond(exchange,
                "{\"month\":\"2026-09\",\"totalCalls\":3,\"totalCostUsd\":0.012,"
                        + "\"departments\":[{\"departmentId\":\"d-1\",\"departmentName\":\"研发部\","
                        + "\"calls\":3,\"costUsd\":0.012,\"monthlyBudgetUsd\":null}],"
                        + "\"users\":[{\"username\":\"alice\",\"departmentName\":\"研发部\","
                        + "\"calls\":3,\"costUsd\":0.012,\"monthlyUsd\":2.0}],"
                        + "\"models\":[{\"modelAlias\":\"m1\",\"calls\":3,\"costUsd\":0.012}]}"));
        server.createContext("/api/admin/quota/limits", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, "{\"monthlyBudgetUsd\":10.5,\"monthlyPerUserUsd\":null,"
                        + "\"departments\":[{\"id\":\"d-1\",\"name\":\"研发部\",\"monthlyUsd\":5.0}],"
                        + "\"userOverrides\":[{\"username\":\"alice\",\"departmentName\":\"研发部\","
                        + "\"monthlyUsd\":2.0}]}");
            } else {
                respond(exchange, "{\"monthlyBudgetUsd\":20.0,\"monthlyPerUserUsd\":2.0,"
                        + "\"departments\":[{\"id\":\"d-1\",\"name\":\"研发部\",\"monthlyUsd\":5.0}],"
                        + "\"userOverrides\":[{\"username\":\"alice\",\"departmentName\":\"研发部\","
                        + "\"monthlyUsd\":2.0}]}");
            }
        });
        server.createContext("/api/admin/quota/limits/department", exchange -> respond(exchange,
                "{\"monthlyBudgetUsd\":20.0,\"monthlyPerUserUsd\":2.0,"
                        + "\"departments\":[{\"id\":\"d-1\",\"name\":\"研发部\",\"monthlyUsd\":8.0}],"
                        + "\"userOverrides\":[]}"));
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("admin", "pw");

            var summary = client.quotaSummary();
            assertEquals("2026-09", summary.month());
            assertEquals(3, summary.totalCalls());
            assertEquals(1, summary.departments().size());
            assertEquals("研发部", summary.departments().get(0).departmentName());
            assertEquals(1, summary.users().size());
            assertEquals("研发部", summary.users().get(0).departmentName());
            assertEquals(2.0, summary.users().get(0).monthlyUsd(), 0.0001);

            var limits = client.quotaLimits();
            assertEquals(10.5, limits.monthlyBudgetUsd(), 0.0001);
            assertNull(limits.monthlyPerUserUsd());
            assertEquals(1, limits.departments().size());
            assertEquals(5.0, limits.departments().get(0).monthlyUsd(), 0.0001);
            assertEquals(1, limits.userOverrides().size());

            var updated = client.updateQuotaLimits(20.0, 2.0);
            assertEquals(20.0, updated.monthlyBudgetUsd(), 0.0001);
            assertEquals(2.0, updated.monthlyPerUserUsd(), 0.0001);

            var deptLimit = client.setDepartmentLimit("d-1", 8.0);
            assertEquals(8.0, deptLimit.departments().get(0).monthlyUsd(), 0.0001);
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
}
