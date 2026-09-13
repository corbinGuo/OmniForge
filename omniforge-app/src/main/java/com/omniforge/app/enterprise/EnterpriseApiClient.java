package com.omniforge.app.enterprise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 企业版服务端 API 客户端（C-tier 批次 4-2 扩展，JDK HttpClient 零新依赖）。
 *
 * <p><b>Token 自动注入（设计确认 #4）</b>：{@link #login} 成功后 token 保存于
 * 客户端内部（内存，重启需重新登录——设计确认 #1），后续所有请求自动携带
 * {@code Authorization: Bearer <token>}，调用方无需再传。</p>
 *
 * <p>端点：POST /api/auth/login · POST /api/agent/chat（sessionId 续接/新建）·
 * GET /api/sessions · PATCH /api/sessions/{id}/rename · DELETE /api/sessions/{id} ·
 * GET /api/sessions/{id}/messages。</p>
 */
public class EnterpriseApiClient {

    static {
        // 放行受限头 Connection（即用即关，防止 java.net.http keep-alive 池无界堆积连接）。
        // 必须在 jdk.internal.net.http Utils 类初始化前设置（类首次加载时即完成）。
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
        }
    }

    /** 会话概要（GET /api/sessions 条目） */
    public record SessionSummary(String id, String name, LocalDateTime createdAt) {
    }

    /** 会话消息（GET /api/sessions/{id}/messages 条目） */
    public record SessionMessage(String role, String content, LocalDateTime createdAt) {
    }

    /** Agent 对话结果（POST /api/agent/chat） */
    public record ChatResult(String text, String stopReason, String modelAlias, String sessionId) {
    }

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    /** 内存 token（设计确认 #1：不落盘，重启重新登录） */
    private volatile String token;

    public EnterpriseApiClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    EnterpriseApiClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 是否已登录（内存 token 存在） */
    public boolean isLoggedIn() {
        return token != null && !token.isBlank();
    }

    /** 退出登录（仅清除内存 token） */
    public void logout() {
        token = null;
    }

    /** 登录结果：token 内部持有，username/role 供 UI 展示（企业版 P2 角色感知） */
    public record LoginResult(String token, String username, String role) {
    }

    /** 登录换取 JWT 并自动保存（设计确认 #4）；失败抛 IllegalStateException（含 HTTP 状态） */
    public LoginResult login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("username", username, "password", password));
        HttpResponse<String> response = send("POST", "/api/auth/login", body);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("登录失败：HTTP " + response.statusCode() + "（账号或密码错误）");
        }
        JsonNode root = objectMapper.readTree(response.body());
        token = root.path("token").asText("");
        return new LoginResult(token, root.path("username").asText(""),
                root.path("role").asText(""));
    }

    /** 调用服务端 Agent（sessionId 空 = 自动新建会话；响应带回实际 sessionId） */
    public ChatResult chat(String sessionId, String prompt) throws Exception {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("prompt", prompt);
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId);
        }
        HttpResponse<String> response = send("POST", "/api/agent/chat",
                objectMapper.writeValueAsString(payload));
        if (response.statusCode() != 200) {
            throw statusError("Agent 调用失败", response);
        }
        JsonNode root = objectMapper.readTree(response.body());
        return new ChatResult(root.path("text").asText(""),
                root.path("stopReason").asText(""),
                root.path("modelAlias").asText(""),
                root.path("sessionId").asText(""));
    }

    /** 当前用户会话列表（时间倒序） */
    public List<SessionSummary> listSessions() throws Exception {
        HttpResponse<String> response = send("GET", "/api/sessions", null);
        if (response.statusCode() != 200) {
            throw statusError("会话列表拉取失败", response);
        }
        List<SessionSummary> sessions = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            sessions.add(new SessionSummary(node.path("id").asText(""),
                    node.path("name").asText(""), parseDateTime(node.path("createdAt").asText(""))));
        }
        return sessions;
    }

    /** 重命名会话 */
    public SessionSummary renameSession(String sessionId, String name) throws Exception {
        HttpResponse<String> response = send("PATCH", "/api/sessions/" + sessionId + "/rename",
                objectMapper.writeValueAsString(java.util.Map.of("name", name)));
        if (response.statusCode() != 200) {
            throw statusError("重命名失败", response);
        }
        JsonNode node = objectMapper.readTree(response.body());
        return new SessionSummary(node.path("id").asText(""), node.path("name").asText(""),
                parseDateTime(node.path("createdAt").asText("")));
    }

    /** 删除会话 */
    public void deleteSession(String sessionId) throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/sessions/" + sessionId, null);
        if (response.statusCode() != 200) {
            throw statusError("删除失败", response);
        }
    }

    /** 会话消息（时间正序，恢复会话用） */
    public List<SessionMessage> sessionMessages(String sessionId) throws Exception {
        HttpResponse<String> response = send("GET", "/api/sessions/" + sessionId + "/messages", null);
        if (response.statusCode() != 200) {
            throw statusError("消息拉取失败", response);
        }
        List<SessionMessage> messages = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            messages.add(new SessionMessage(node.path("role").asText(""),
                    node.path("content").asText(""), parseDateTime(node.path("createdAt").asText(""))));
        }
        return messages;
    }

    /** D8：协作阶段消息落库（role 固定 collab；viewer 403 / 503 已在服务端标注） */
    public void appendSessionMessage(String sessionId, String content) throws Exception {
        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("role", "collab").put("content", content));
        HttpResponse<String> response = send("POST",
                "/api/sessions/" + encode(sessionId) + "/messages", body, 30);
        if (response.statusCode() != 200) {
            throw statusError("协作记录落库失败", response);
        }
    }

    // ---------- 管理（P2-2）：/api/admin/*，需 role=admin（服务端 403 兜底） ----------

    public record DepartmentDto(String id, String name, long memberCount) {
    }

    public record UserDto(String id, String username, String name, String mobile,
                          String departmentId, String departmentName, String role,
                          boolean enabled, LocalDateTime createdAt) {
    }

    public record RoleDto(String id, String name, boolean builtIn, List<String> permissions) {
    }

    public record CapabilityDto(String id, String name) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    public record UserCreate(String username, String name, String mobile,
                             String departmentId, String role, String initialPassword) {
    }

    public record UserUpdate(String name, String mobile, String departmentId,
                             String role, Boolean enabled) {
    }

    public record RoleSave(String name, List<String> permissions) {
    }

    /** 部门列表（可含 keyword 名称过滤） */
    public List<DepartmentDto> listDepartments(String keyword) throws Exception {
        HttpResponse<String> response = send("GET", "/api/admin/departments"
                + (keyword == null || keyword.isBlank() ? "" : "?keyword=" + keyword), null);
        if (response.statusCode() != 200) {
            throw statusError("部门列表拉取失败", response);
        }
        List<DepartmentDto> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new DepartmentDto(node.path("id").asText(), node.path("name").asText(),
                    node.path("memberCount").asLong()));
        }
        return items;
    }

    public DepartmentDto createDepartment(String name) throws Exception {
        return parseDepartment(send("POST", "/api/admin/departments",
                objectMapper.writeValueAsString(Map.of("name", name))), "部门创建失败");
    }

    public DepartmentDto renameDepartment(String id, String name) throws Exception {
        return parseDepartment(send("PUT", "/api/admin/departments/" + id,
                objectMapper.writeValueAsString(Map.of("name", name))), "部门重命名失败");
    }

    public void deleteDepartment(String id) throws Exception {
        requireOk(send("DELETE", "/api/admin/departments/" + id, null), "部门删除失败");
    }

    /** 用户分页列表（page 0 基；keyword/departmentId/role/enabled 可为空） */
    public Page<UserDto> listUsers(String keyword, String departmentId, String role,
                                   Boolean enabled, int page, int size) throws Exception {
        StringBuilder query = new StringBuilder("?page=").append(page).append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            query.append("&keyword=").append(keyword);
        }
        if (departmentId != null && !departmentId.isBlank()) {
            query.append("&departmentId=").append(departmentId);
        }
        if (role != null && !role.isBlank()) {
            query.append("&role=").append(role);
        }
        if (enabled != null) {
            query.append("&enabled=").append(enabled);
        }
        HttpResponse<String> response = send("GET", "/api/admin/users" + query, null);
        if (response.statusCode() != 200) {
            throw statusError("用户列表拉取失败", response);
        }
        return parsePage(response, UserDto.class);
    }

    public UserDto createUser(UserCreate request) throws Exception {
        return parseUser(send("POST", "/api/admin/users",
                objectMapper.writeValueAsString(request)), "用户创建失败");
    }

    public UserDto updateUser(String id, UserUpdate request) throws Exception {
        return parseUser(send("PUT", "/api/admin/users/" + id,
                objectMapper.writeValueAsString(request)), "用户更新失败");
    }

    public void deleteUser(String id) throws Exception {
        requireOk(send("DELETE", "/api/admin/users/" + id, null), "用户删除失败");
    }

    public List<RoleDto> listRoles() throws Exception {
        HttpResponse<String> response = send("GET", "/api/admin/roles", null);
        if (response.statusCode() != 200) {
            throw statusError("角色列表拉取失败", response);
        }
        List<RoleDto> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new RoleDto(node.path("id").asText(), node.path("name").asText(),
                    node.path("builtIn").asBoolean(), toList(node.path("permissions"))));
        }
        return items;
    }

    public RoleDto createRole(RoleSave request) throws Exception {
        return parseRole(send("POST", "/api/admin/roles",
                objectMapper.writeValueAsString(request)), "角色创建失败");
    }

    public RoleDto updateRole(String id, RoleSave request) throws Exception {
        return parseRole(send("PUT", "/api/admin/roles/" + id,
                objectMapper.writeValueAsString(request)), "角色更新失败");
    }

    public void deleteRole(String id) throws Exception {
        requireOk(send("DELETE", "/api/admin/roles/" + id, null), "角色删除失败");
    }

    public List<CapabilityDto> capabilities() throws Exception {
        HttpResponse<String> response = send("GET", "/api/admin/capabilities", null);
        if (response.statusCode() != 200) {
            throw statusError("能力列表拉取失败", response);
        }
        List<CapabilityDto> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new CapabilityDto(node.path("id").asText(), node.path("name").asText()));
        }
        return items;
    }

    // ---- admin 解析辅助 ----

    private DepartmentDto parseDepartment(HttpResponse<String> response, String action)
            throws Exception {
        requireOk(response, action);
        JsonNode node = objectMapper.readTree(response.body());
        return new DepartmentDto(node.path("id").asText(), node.path("name").asText(),
                node.path("memberCount").asLong());
    }

    private UserDto parseUser(HttpResponse<String> response, String action) throws Exception {
        requireOk(response, action);
        JsonNode node = objectMapper.readTree(response.body());
        return new UserDto(node.path("id").asText(), node.path("username").asText(),
                textOrEmpty(node.path("name")), textOrEmpty(node.path("mobile")),
                node.path("departmentId").asText(), textOrEmpty(node.path("departmentName")),
                node.path("role").asText(), node.path("enabled").asBoolean(),
                parseDateTime(node.path("createdAt").asText("")));
    }

    private RoleDto parseRole(HttpResponse<String> response, String action) throws Exception {
        requireOk(response, action);
        JsonNode node = objectMapper.readTree(response.body());
        return new RoleDto(node.path("id").asText(), node.path("name").asText(),
                node.path("builtIn").asBoolean(), toList(node.path("permissions")));
    }

    private <T> Page<T> parsePage(HttpResponse<String> response, Class<T> itemType)
            throws Exception {
        JsonNode root = objectMapper.readTree(response.body());
        List<T> items = new ArrayList<>();
        for (JsonNode node : root.path("items")) {
            if (itemType == UserDto.class) {
                @SuppressWarnings("unchecked")
                T item = (T) new UserDto(node.path("id").asText(), node.path("username").asText(),
                        textOrEmpty(node.path("name")), textOrEmpty(node.path("mobile")),
                        node.path("departmentId").asText(), textOrEmpty(node.path("departmentName")),
                        node.path("role").asText(), node.path("enabled").asBoolean(),
                        parseDateTime(node.path("createdAt").asText("")));
                items.add(item);
            }
        }
        return new Page<>(items, root.path("total").asLong(),
                root.path("page").asInt(), root.path("size").asInt());
    }

    private static List<String> toList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(node.asText());
        }
        return result;
    }

    /** 可选文本字段：NullNode/MissingNode 归一为空串，避免 JSON null 被 asText() 渲染成 "null" */
    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText();
    }

    /** 非 200 即抛：401 = 登录过期；否则携带服务端中文 message（无则 HTTP 状态） */
    private void requireOk(HttpResponse<String> response, String action) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusError(action, response);
        }
    }

    /** 非 200 状态归类（企业版 P2/P2-2）：401 = 登录过期；403 = 无权；其余 = 普通失败（携带服务端 message） */
    private RuntimeException statusError(String action, HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            return new EnterpriseAuthException("登录已过期，请重新登录");
        }
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            message = root.path("message").asText(null);
        } catch (Exception ignored) {
            // 非 JSON 错误体：回落 HTTP 状态
        }
        if (response.statusCode() == 403) {
            return new IllegalStateException("无权操作（需要管理员角色），如非预期请重新登录");
        }
        return new IllegalStateException(action + "："
                + (message == null || message.isBlank() ? "HTTP " + response.statusCode() : message));
    }

    /** 登录态失效异常（UI 捕获后触发重新登录流程） */
    public static final class EnterpriseAuthException extends RuntimeException {

        public EnterpriseAuthException(String message) {
            super(message);
        }
    }

    // ---------- 企业版多模型协作（方案 B 2026-09-05）：模型清单 + 辩论/讨论/头脑风暴 ----------

    public record ModelOption(String alias, String providerName, Integer contextWindowTokens) {
    }

    public record DebateRun(String topic, List<String> aliases, String judgeAlias,
                            String mode, Integer maxRounds, Double maxCostUsd) {
    }

    public record DebateVerdict(int round, String text) {
    }

    public record DebateOutcome(String text, String mode, List<String> aliases, String judgeAlias,
                                List<DebateVerdict> judgeVerdicts, Map<String, String> finalRound,
                                String winnerAlias, String stopReason, int maxRounds) {
    }

    /** 服务端可用模型（模式选择/勾选用；需登录） */
    public List<ModelOption> listModels() throws Exception {
        HttpResponse<String> response = send("GET", "/api/models", null);
        if (response.statusCode() != 200) {
            throw statusError("模型清单拉取失败", response);
        }
        List<ModelOption> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new ModelOption(node.path("alias").asText(),
                    node.path("providerName").asText(""),
                    node.path("contextWindowTokens").isNull() ? null
                            : node.path("contextWindowTokens").asInt()));
        }
        return items;
    }

    /** 服务端多模型协作（辩论/讨论/头脑风暴，可带裁判）；同步等完成事件（内部 300s 熔断） */
    public DebateOutcome runDebate(DebateRun run) throws Exception {
        HttpResponse<String> response = send("POST", "/api/agent/debate",
                objectMapper.writeValueAsString(run), 330);
        if (response.statusCode() != 200) {
            throw statusError("辩论执行失败", response);
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<DebateVerdict> verdicts = new ArrayList<>();
        for (JsonNode node : root.path("judgeVerdicts")) {
            verdicts.add(new DebateVerdict(node.path("round").asInt(), node.path("text").asText("")));
        }
        Map<String, String> finalRound = new java.util.LinkedHashMap<>();
        root.path("finalRound").fields().forEachRemaining(entry ->
                finalRound.put(entry.getKey(), entry.getValue().asText("")));
        return new DebateOutcome(root.path("text").asText(""),
                root.path("mode").asText(""), toList(root.path("aliases")),
                root.path("judgeAlias").asText(""), verdicts, finalRound,
                root.path("winnerAlias").asText(""), root.path("stopReason").asText(""),
                root.path("maxRounds").asInt());
    }

    /** 本月用量汇总（/api/admin/quota/summary；rev2 含部门/用户/模型） */
    public record QuotaSummary(String month, long totalCalls, double totalCostUsd,
                               List<DepartmentUsage> departments, List<UserUsage> users,
                               List<ModelUsage> models) {
        public record DepartmentUsage(String departmentId, String departmentName, long calls,
                                      double costUsd, Double monthlyBudgetUsd) {
        }
        public record UserUsage(String username, String departmentName, long calls,
                                double costUsd, Double monthlyUsd) {
        }
        public record ModelUsage(String modelAlias, long calls, double costUsd) {
        }
    }

    /** 限额视图（企业默认 + 部门 + 用户覆写 + 模型级） */
    public record QuotaLimits(Double monthlyBudgetUsd, Double monthlyPerUserUsd,
                              List<DepartmentLimit> departments, List<UserLimit> userOverrides,
                              List<ModelLimit> modelLimits) {
        public record DepartmentLimit(String id, String name, Double monthlyUsd) {
        }
        public record UserLimit(String username, String departmentName, Double monthlyUsd) {
        }
        public record ModelLimit(String modelAlias, Long monthlyCalls, Double monthlyUsd) {
        }
    }

    public QuotaSummary quotaSummary() throws Exception {
        HttpResponse<String> response = send("GET", "/api/admin/quota/summary", null);
        if (response.statusCode() != 200) {
            throw statusError("用量汇总拉取失败", response);
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<QuotaSummary.DepartmentUsage> depts = new ArrayList<>();
        for (JsonNode node : root.path("departments")) {
            depts.add(new QuotaSummary.DepartmentUsage(node.path("departmentId").asText(),
                    node.path("departmentName").asText(), node.path("calls").asLong(),
                    node.path("costUsd").asDouble(),
                    optionalDouble(node.path("monthlyBudgetUsd"))));
        }
        List<QuotaSummary.UserUsage> users = new ArrayList<>();
        for (JsonNode node : root.path("users")) {
            users.add(new QuotaSummary.UserUsage(node.path("username").asText(),
                    node.path("departmentName").asText(), node.path("calls").asLong(),
                    node.path("costUsd").asDouble(), optionalDouble(node.path("monthlyUsd"))));
        }
        List<QuotaSummary.ModelUsage> models = new ArrayList<>();
        for (JsonNode node : root.path("models")) {
            models.add(new QuotaSummary.ModelUsage(node.path("modelAlias").asText(),
                    node.path("calls").asLong(), node.path("costUsd").asDouble()));
        }
        return new QuotaSummary(root.path("month").asText(), root.path("totalCalls").asLong(),
                root.path("totalCostUsd").asDouble(), depts, users, models);
    }

    public QuotaLimits quotaLimits() throws Exception {
        return parseQuotaLimits(send("GET", "/api/admin/quota/limits", null), "限额读取失败");
    }

    /** 企业默认限额（null=不限） */
    public QuotaLimits updateQuotaLimits(Double monthlyBudgetUsd, Double monthlyPerUserUsd)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("monthlyBudgetUsd", monthlyBudgetUsd);
        body.put("monthlyPerUserUsd", monthlyPerUserUsd);
        return parseQuotaLimits(send("PUT", "/api/admin/quota/limits",
                objectMapper.writeValueAsString(body)), "限额更新失败");
    }

    /** 部门限额（null=清除） */
    public QuotaLimits setDepartmentLimit(String departmentId, Double monthlyUsd) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("departmentId", departmentId);
        body.put("monthlyUsd", monthlyUsd);
        return parseQuotaLimits(send("PUT", "/api/admin/quota/limits/department",
                objectMapper.writeValueAsString(body)), "部门限额更新失败");
    }

    /** 用户限额（null=删除覆写回企业默认） */
    public QuotaLimits setUserLimit(String username, Double monthlyUsd) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", username);
        body.put("monthlyUsd", monthlyUsd);
        return parseQuotaLimits(send("PUT", "/api/admin/quota/limits/user",
                objectMapper.writeValueAsString(body)), "用户限额更新失败");
    }

    /** 模型级月限额（全 null = 清除该行） */
    public QuotaLimits setModelLimit(String modelAlias, Long monthlyCalls, Double monthlyUsd)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("modelAlias", modelAlias);
        body.put("monthlyCalls", monthlyCalls);
        body.put("monthlyUsd", monthlyUsd);
        return parseQuotaLimits(send("PUT", "/api/admin/quota/limits/model",
                objectMapper.writeValueAsString(body)), "模型限额更新失败");
    }

    private QuotaLimits parseQuotaLimits(HttpResponse<String> response, String action)
            throws Exception {
        requireOk(response, action);
        JsonNode root = objectMapper.readTree(response.body());
        List<QuotaLimits.DepartmentLimit> depts = new ArrayList<>();
        for (JsonNode node : root.path("departments")) {
            depts.add(new QuotaLimits.DepartmentLimit(node.path("id").asText(),
                    node.path("name").asText(), optionalDouble(node.path("monthlyUsd"))));
        }
        List<QuotaLimits.UserLimit> userOverrides = new ArrayList<>();
        for (JsonNode node : root.path("userOverrides")) {
            userOverrides.add(new QuotaLimits.UserLimit(node.path("username").asText(),
                    node.path("departmentName").asText(),
                    optionalDouble(node.path("monthlyUsd"))));
        }
        List<QuotaLimits.ModelLimit> modelLimits = new ArrayList<>();
        for (JsonNode node : root.path("modelLimits")) {
            modelLimits.add(new QuotaLimits.ModelLimit(node.path("modelAlias").asText(),
                    node.path("monthlyCalls").isValueNode()
                            ? node.path("monthlyCalls").asLong() : null,
                    optionalDouble(node.path("monthlyUsd"))));
        }
        return new QuotaLimits(optionalDouble(root.path("monthlyBudgetUsd")),
                optionalDouble(root.path("monthlyPerUserUsd")), depts, userOverrides, modelLimits);
    }

    private static Double optionalDouble(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asDouble();
    }

    // ---------- 企业知识库（服务端共享库；member/admin 管理、viewer 只读由服务端强校验） ----------

    public record KbDocument(String fileName, String category, int chunks, int chars) {
    }

    public record KbSearchHit(String fileName, int chunkIndex, String content, double score) {
    }

    public List<KbDocument> listKbDocuments() throws Exception {
        HttpResponse<String> response = send("GET", "/api/knowledge/documents", null);
        requireOk(response, "知识库清单拉取失败");
        List<KbDocument> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new KbDocument(node.path("fileName").asText(),
                    node.path("category").asText(), node.path("chunks").asInt(),
                    node.path("chars").asInt()));
        }
        return items;
    }

    public List<String> listKbCategories() throws Exception {
        HttpResponse<String> response = send("GET", "/api/knowledge/categories", null);
        requireOk(response, "知识库分类拉取失败");
        List<String> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(node.asText());
        }
        return items;
    }

    public List<KbSearchHit> kbSearch(String query, int topK, String category) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        body.put("topK", topK);
        if (category != null && !category.isBlank()) {
            body.put("category", category);
        }
        HttpResponse<String> response = send("POST", "/api/knowledge/search",
                objectMapper.writeValueAsString(body));
        requireOk(response, "知识检索失败");
        List<KbSearchHit> items = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            items.add(new KbSearchHit(node.path("fileName").asText(),
                    node.path("chunkIndex").asInt(), node.path("content").asText(""),
                    node.path("score").asDouble()));
        }
        return items;
    }

    /** 文档全文预览（服务端按切片拼接，超大文本服务端截断） */
    public String kbContent(String fileName) throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/knowledge/documents/" + encode(fileName) + "/content", null, 60);
        requireOk(response, "文档预览失败");
        return response.body();
    }

    /** 下载原件字节（历史数据未留存原件 → 服务端 404 中文 message 上抛） */
    public byte[] kbOriginal(String fileName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/knowledge/documents/" + encode(fileName) + "/file"))
                .timeout(Duration.ofSeconds(60))
                .header("Connection", "close")
                .header("Authorization", "Bearer " + (token == null ? "" : token))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.ConnectException e) {
            throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：连接被拒绝");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：" + e.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = null;
            try {
                message = objectMapper.readTree(new String(response.body(), StandardCharsets.UTF_8))
                        .path("message").asText(null);
            } catch (Exception ignored) {
                // 非 JSON 错误体：回落 HTTP 状态
            }
            throw new IllegalStateException("原件下载失败："
                    + (message == null || message.isBlank() ? "HTTP " + response.statusCode() : message));
        }
        return response.body();
    }

    /** 上传文档（multipart：file + 可选 category）；返回入库切片数 */
    public int kbUpload(String fileName, byte[] content, String category) throws Exception {
        String boundary = "----omniforge" + Long.toHexString(System.nanoTime());
        byte[] body = buildMultipart(boundary, fileName, content, category);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/knowledge/documents"))
                .timeout(Duration.ofSeconds(300))
                // 即用即关，避免 keep-alive 池堆积连接（见 send() 注释）
                .header("Connection", "close")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + (token == null ? "" : token))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：连接被拒绝");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：" + e.getMessage());
        }
        requireOk(response, "知识文档入库失败");
        return objectMapper.readTree(response.body()).size();
    }

    public void kbDeleteDocument(String fileName) throws Exception {
        requireOk(send("DELETE", "/api/knowledge/documents/" + encode(fileName), null),
                "知识文档删除失败");
    }

    public void kbUpdateCategory(String fileName, String category) throws Exception {
        requireOk(send("PUT", "/api/knowledge/documents/" + encode(fileName) + "/category",
                objectMapper.writeValueAsString(Map.of("category",
                        category == null || category.isBlank() ? null : category))),
                "分类更新失败");
    }

    public void kbDeleteCategory(String category) throws Exception {
        requireOk(send("DELETE", "/api/knowledge/category/" + encode(category), null),
                "整类删除失败");
    }

    /** 补 OCR（扫描版 PDF/图片原件已留存时）：重新识别并替换文档内容 */
    public void kbReOcr(String fileName) throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/knowledge/documents/" + encode(fileName) + "/reocr", "{}", 300);
        requireOk(response, "OCR 补识别失败");
    }

    // ---------- 多模型协作闭环（A1-A4）：档案 + 四阶段推进 ----------

    /** 协作闭环运行快照（与服务端 RunDto 对齐） */
    public record CollabRunDto(String id, String sessionId, String mode, String topic,
                               List<String> aliases,
                               String judgeAlias, String status, int maxRounds,
                               String conclusion, String executionTask, String executorAlias,
                               String executionOutput, String reviewerAlias, String reviewVerdict,
                               String reviewReason, String createdBy, String createdAt,
                               String updatedAt) {
    }

    /** 档案分页（content + 总量） */
    public record CollabPage(List<CollabRunDto> items, long total) {
    }

    /** 档案列表（本企业倒序分页） */
    public CollabPage collabRuns(int page, int size) throws Exception {
        return collabRuns(null, page, size);
    }

    /** 档案列表（按会话过滤——D5 文档产出 Q1：导出该会话内发起的协作） */
    public CollabPage collabRuns(String sessionId, int page, int size) throws Exception {
        StringBuilder query = new StringBuilder("/api/collab/runs?page=").append(page)
                .append("&size=").append(size);
        if (sessionId != null && !sessionId.isBlank()) {
            query.append("&sessionId=").append(encode(sessionId));
        }
        HttpResponse<String> response = send("GET", query.toString(), null);
        requireOk(response, "闭环档案读取失败");
        JsonNode root = objectMapper.readTree(response.body());
        List<CollabRunDto> items = new ArrayList<>();
        for (JsonNode node : root.path("content")) {
            items.add(parseCollab(node));
        }
        return new CollabPage(items, root.path("totalElements").asLong());
    }

    /** 运行详情 */
    public CollabRunDto collabDetail(String runId) throws Exception {
        HttpResponse<String> response = send("GET", "/api/collab/runs/" + encode(runId), null);
        requireOk(response, "闭环详情读取失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    /** 讨论全文 */
    public String collabTranscript(String runId) throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/collab/runs/" + encode(runId) + "/transcript", null, 60);
        requireOk(response, "讨论记录读取失败");
        return response.body();
    }

    /** D7 Q6-B：全轮次结构化讨论记录（旧 run 服务端返回 "{}"） */
    public String collabTranscriptData(String runId) throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/collab/runs/" + encode(runId) + "/transcript-data", null, 60);
        requireOk(response, "讨论记录读取失败");
        return response.body();
    }

    /** 建运行 + 讨论（返回快照；systemText 为背景/角色指令，可空；sessionId 可空=不关联会话） */
    public CollabRunDto collabCreate(String topic, List<String> aliases, String judgeAlias,
                                     String mode, Integer maxRounds, Double maxCostUsd,
                                     String systemText, String sessionId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("topic", topic);
        body.put("aliases", aliases);
        body.put("judgeAlias", judgeAlias);
        body.put("mode", mode);
        body.put("maxRounds", maxRounds);
        body.put("maxCostUsd", maxCostUsd);
        if (systemText != null && !systemText.isBlank()) {
            body.put("systemText", systemText);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        HttpResponse<String> response = send("POST", "/api/collab/runs",
                objectMapper.writeValueAsString(body), 400);
        requireOk(response, "协作启动失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    /** 结论 */
    public CollabRunDto collabConclude(String runId) throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/collab/runs/" + encode(runId) + "/conclude", "{}", 300);
        requireOk(response, "结论生成失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    /** 执行（task 缺省=结论；executorAlias 必填或服务端默认） */
    public CollabRunDto collabExecute(String runId, String task, String executorAlias)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("task", task);
        body.put("executorAlias", executorAlias);
        HttpResponse<String> response = send("POST",
                "/api/collab/runs/" + encode(runId) + "/execute",
                objectMapper.writeValueAsString(body), 400);
        requireOk(response, "执行失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    /** 复检验收 */
    public CollabRunDto collabReview(String runId, String reviewerAlias) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("reviewerAlias", reviewerAlias);
        HttpResponse<String> response = send("POST",
                "/api/collab/runs/" + encode(runId) + "/review",
                objectMapper.writeValueAsString(body), 300);
        requireOk(response, "验收失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    /** revised 重试 */
    public CollabRunDto collabRetry(String runId, String task, String executorAlias)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("task", task);
        body.put("executorAlias", executorAlias);
        HttpResponse<String> response = send("POST",
                "/api/collab/runs/" + encode(runId) + "/retry",
                objectMapper.writeValueAsString(body), 400);
        requireOk(response, "重试失败");
        return parseCollab(objectMapper.readTree(response.body()));
    }

    public void collabDelete(String runId) throws Exception {
        requireOk(send("DELETE", "/api/collab/runs/" + encode(runId), null),
                "闭环记录删除失败");
    }

    private CollabRunDto parseCollab(JsonNode node) {
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : node.path("aliases")) {
            aliases.add(alias.asText());
        }
        return new CollabRunDto(node.path("id").asText(),
                textOrEmpty(node.path("sessionId")),
                textOrEmpty(node.path("mode")), textOrEmpty(node.path("topic")), aliases,
                node.path("judgeAlias").isValueNode() ? textOrEmpty(node.path("judgeAlias")) : null,
                textOrEmpty(node.path("status")), node.path("maxRounds").asInt(),
                node.path("conclusion").isValueNode() ? textOrEmpty(node.path("conclusion")) : null,
                node.path("executionTask").isValueNode() ? textOrEmpty(node.path("executionTask")) : null,
                node.path("executorAlias").isValueNode() ? textOrEmpty(node.path("executorAlias")) : null,
                node.path("executionOutput").isValueNode() ? textOrEmpty(node.path("executionOutput")) : null,
                node.path("reviewerAlias").isValueNode() ? textOrEmpty(node.path("reviewerAlias")) : null,
                node.path("reviewVerdict").isValueNode() ? textOrEmpty(node.path("reviewVerdict")) : null,
                node.path("reviewReason").isValueNode() ? textOrEmpty(node.path("reviewReason")) : null,
                textOrEmpty(node.path("createdBy")), textOrEmpty(node.path("createdAt")),
                textOrEmpty(node.path("updatedAt")));
    }

    private static byte[] buildMultipart(String boundary, String fileName, byte[] content,
                                         String category) throws Exception {
        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + crlf);
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + crlf);
        write(out, "Content-Type: application/octet-stream" + crlf + crlf);
        out.write(content);
        write(out, crlf);
        if (category != null && !category.isBlank()) {
            write(out, "--" + boundary + crlf);
            write(out, "Content-Disposition: form-data; name=\"category\"" + crlf + crlf);
            write(out, category + crlf);
        }
        write(out, "--" + boundary + "--" + crlf);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private HttpResponse<String> send(String method, String path, String jsonBody)
            throws Exception {
        return send(method, path, jsonBody, 60);
    }

    private HttpResponse<String> send(String method, String path, String jsonBody,
                                      int timeoutSeconds) throws Exception {
        boolean idempotent = "GET".equals(method) || "DELETE".equals(method) || "PUT".equals(method);
        long[] backoffMs = {400, 1500};
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    // 每请求即用即关：java.net.http keep-alive 池在反复突发下会无界堆积
                    // ESTABLISHED 连接（实测 1.6 万条）拖垮服务端 accept 队列 → 连接被拒
                    .header("Connection", "close")
                    .header("Content-Type", "application/json");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpRequest.BodyPublisher body = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
            try {
                return httpClient.send(builder.method(method, body).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (java.net.ConnectException e) {
                // 服务端瞬时过载/重启：幂等请求带退避重试（最多 3 次），非幂等直接抛
                if (idempotent && attempt < backoffMs.length) {
                    sleepQuietly(backoffMs[attempt]);
                    continue;
                }
                // 服务端不可达（企业版 P2）：明确提示而不是裸堆栈
                throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：连接被拒绝");
            } catch (java.net.http.HttpConnectTimeoutException e) {
                throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：连接超时");
            } catch (java.io.IOException e) {
                // 连接被重置/复用失效：重试一次（幂等与非幂等均可——请求可能未送达）
                if (attempt < 1) {
                    sleepQuietly(200);
                    continue;
                }
                throw new IllegalStateException("无法连接服务端（" + baseUrl + "）：" + e.getMessage());
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
        } catch (Exception e) {
            return LocalDateTime.parse(raw);
        }
    }
}
