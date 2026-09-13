package com.omniforge.app.enterprise;

import com.omniforge.ui.enterprise.EnterpriseAdminAccess;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.DepartmentRow;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.RoleRow;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.UserRow;
import com.omniforge.ui.enterprise.EnterpriseBridge;
import com.omniforge.ui.enterprise.EnterpriseBridge.DebateOutcome;
import com.omniforge.ui.enterprise.EnterpriseBridge.DebateRun;
import com.omniforge.ui.enterprise.EnterpriseBridge.ModelOption;

import java.nio.file.Path;
import java.util.List;

/**
 * EnterpriseBridge 实现（装配层接线）：把企业版会话管理器包装为 ui 桥接 SPI；
 * P2-2 增 admin()/role()（管理操作透传 EnterpriseApiClient，服务端强校验 role=admin）。
 */
public class EnterpriseBridgeImpl implements EnterpriseBridge {

    private final EnterpriseSessionManager manager;
    private final EnterpriseSettingsStore store;
    private final Path settingsFile;
    private final String initialServerUrl;

    public EnterpriseBridgeImpl(EnterpriseSessionManager manager, EnterpriseSettingsStore store,
                                Path settingsFile, String initialServerUrl) {
        this.manager = manager;
        this.store = store;
        this.settingsFile = settingsFile;
        this.initialServerUrl = initialServerUrl;
    }

    @Override
    public String initialServerUrl() {
        return initialServerUrl;
    }

    @Override
    public String login(String serverUrl, String username, String password) throws Exception {
        manager.setServerUrl(serverUrl);
        return manager.login(username, password);
    }

    @Override
    public void saveServerUrl(String serverUrl) {
        try {
            store.save(settingsFile, new EnterpriseSettings(serverUrl));
        } catch (Exception e) {
            // 地址保存失败不影响登录会话（下次启动回退旧值）
        }
    }

    @Override
    public List<SessionItem> sessions() {
        return manager.sessions().stream()
                .map(session -> new SessionItem(session.id(), session.name(), session.createdAt()))
                .toList();
    }

    @Override
    public List<SessionItem> refreshSessions() throws Exception {
        return guard(() -> {
            manager.refreshSessions();
            return manager.sessions().stream()
                    .map(session -> new SessionItem(session.id(), session.name(), session.createdAt()))
                    .toList();
        });
    }

    @Override
    public void renameSession(String sessionId, String name) throws Exception {
        try {
            manager.renameSession(sessionId, name);
        } catch (EnterpriseApiClient.EnterpriseAuthException e) {
            throw new EnterpriseBridge.AuthExpiredException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteSession(String sessionId) throws Exception {
        try {
            manager.deleteSession(sessionId);
        } catch (EnterpriseApiClient.EnterpriseAuthException e) {
            throw new EnterpriseBridge.AuthExpiredException(e.getMessage(), e);
        }
    }

    @Override
    public ChatOutcome chat(String sessionId, String prompt) throws Exception {
        try {
            EnterpriseApiClient.ChatResult result = manager.chat(sessionId, prompt);
            return new ChatOutcome(result.text(), result.sessionId());
        } catch (EnterpriseApiClient.EnterpriseAuthException e) {
            throw new EnterpriseBridge.AuthExpiredException(e.getMessage(), e);
        }
    }

    @Override
    public List<ChatMessage> messages(String sessionId) throws Exception {
        try {
            return manager.sessionMessages(sessionId).stream()
                    .map(message -> new ChatMessage(message.role(), message.content(), message.createdAt()))
                    .toList();
        } catch (EnterpriseApiClient.EnterpriseAuthException e) {
            throw new EnterpriseBridge.AuthExpiredException(e.getMessage(), e);
        }
    }

    @Override
    public String role() {
        return manager.role();
    }

    @Override
    public void logout() {
        manager.logout();
    }

    @Override
    public List<com.omniforge.ui.enterprise.EnterpriseBridge.KbDocument> kbDocuments()
            throws Exception {
        return guard(() -> manager.api().listKbDocuments().stream()
                .map(dto -> new com.omniforge.ui.enterprise.EnterpriseBridge.KbDocument(
                        dto.fileName(), dto.category(), dto.chunks(), dto.chars()))
                .toList());
    }

    @Override
    public List<String> kbCategories() throws Exception {
        return guard(() -> manager.api().listKbCategories());
    }

    @Override
    public List<com.omniforge.ui.enterprise.EnterpriseBridge.KbSearchHit> kbSearch(
            String query, int topK, String category) throws Exception {
        return guard(() -> manager.api().kbSearch(query, topK, category).stream()
                .map(hit -> new com.omniforge.ui.enterprise.EnterpriseBridge.KbSearchHit(
                        hit.fileName(), hit.content(), hit.score()))
                .toList());
    }

    @Override
    public String kbDocumentContent(String fileName) throws Exception {
        return guard(() -> manager.api().kbContent(fileName));
    }

    @Override
    public byte[] kbOriginalFile(String fileName) throws Exception {
        return guard(() -> manager.api().kbOriginal(fileName));
    }

    @Override
    public int kbUpload(String fileName, byte[] content, String category) throws Exception {
        return guard(() -> manager.api().kbUpload(fileName, content, category));
    }

    @Override
    public void kbDeleteDocument(String fileName) throws Exception {
        guard(() -> {
            manager.api().kbDeleteDocument(fileName);
            return null;
        });
    }

    @Override
    public void kbUpdateCategory(String fileName, String category) throws Exception {
        guard(() -> {
            manager.api().kbUpdateCategory(fileName, category);
            return null;
        });
    }

    @Override
    public void kbReOcr(String fileName) throws Exception {
        guard(() -> {
            manager.api().kbReOcr(fileName);
            return null;
        });
    }

    @Override
    public void kbDeleteCategory(String category) throws Exception {
        guard(() -> {
            manager.api().kbDeleteCategory(category);
            return null;
        });
    }

    @Override
    public List<ModelOption> models() throws Exception {
        return guard(() -> manager.api().listModels().stream()
                .map(dto -> new ModelOption(dto.alias(), dto.providerName()))
                .toList());
    }

    @Override
    public DebateOutcome debate(DebateRun run) throws Exception {
        return guard(() -> {
            var out = manager.api().runDebate(new EnterpriseApiClient.DebateRun(
                    run.topic(), run.aliases(), run.judgeAlias(), run.mode(),
                    run.maxRounds(), run.maxCostUsd()));
            return new DebateOutcome(out.text(), out.mode(), out.judgeAlias(),
                    out.judgeVerdicts().stream().map(EnterpriseApiClient.DebateVerdict::text).toList(),
                    out.winnerAlias(), out.stopReason());
        });
    }

    // ---------- 多模型协作闭环（A1-A4） ----------

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabList collabRuns(int page, int size)
            throws Exception {
        return collabRuns(null, page, size);
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabList collabRuns(
            String sessionId, int page, int size) throws Exception {
        return guard(() -> {
            var dto = manager.api().collabRuns(sessionId, page, size);
            return new com.omniforge.ui.enterprise.EnterpriseBridge.CollabList(
                    dto.items().stream().map(EnterpriseBridgeImpl::ofCollab).toList(),
                    dto.total());
        });
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabDetail(String runId)
            throws Exception {
        return guard(() -> ofCollab(manager.api().collabDetail(runId)));
    }

    @Override
    public String collabTranscript(String runId) throws Exception {
        return guard(() -> manager.api().collabTranscript(runId));
    }

    @Override
    public String collabTranscriptData(String runId) throws Exception {
        return guard(() -> manager.api().collabTranscriptData(runId));
    }

    @Override
    public void appendSessionMessage(String sessionId, String content) throws Exception {
        guard(() -> {
            manager.appendSessionMessage(sessionId, content);
            return null;
        });
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabCreate(
            String topic, List<String> aliases, String judgeAlias, String mode,
            Integer maxRounds, Double maxCostUsd, String systemText, String sessionId)
            throws Exception {
        return guard(() -> ofCollab(manager.api().collabCreate(
                topic, aliases, judgeAlias, mode, maxRounds, maxCostUsd, systemText, sessionId)));
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabConclude(String runId)
            throws Exception {
        return guard(() -> ofCollab(manager.api().collabConclude(runId)));
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabExecute(
            String runId, String task, String executorAlias) throws Exception {
        return guard(() -> ofCollab(manager.api().collabExecute(runId, task, executorAlias)));
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabReview(
            String runId, String reviewerAlias) throws Exception {
        return guard(() -> ofCollab(manager.api().collabReview(runId, reviewerAlias)));
    }

    @Override
    public com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView collabRetry(
            String runId, String task, String executorAlias) throws Exception {
        return guard(() -> ofCollab(manager.api().collabRetry(runId, task, executorAlias)));
    }

    @Override
    public void collabDelete(String runId) throws Exception {
        guard(() -> {
            manager.api().collabDelete(runId);
            return null;
        });
    }

    private static com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView ofCollab(
            EnterpriseApiClient.CollabRunDto dto) {
        return new com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView(
                dto.id(), dto.sessionId(), dto.mode(), dto.topic(), dto.aliases(),
                dto.judgeAlias(), dto.status(),
                dto.maxRounds(), dto.conclusion(), dto.executionTask(), dto.executorAlias(),
                dto.executionOutput(), dto.reviewerAlias(), dto.reviewVerdict(), dto.reviewReason(),
                dto.createdBy(), dto.createdAt(), dto.updatedAt());
    }

    @Override
    public EnterpriseAdminAccess admin() {
        EnterpriseApiClient client = manager.api();
        return new EnterpriseAdminAccess() {
            @Override
            public List<DepartmentRow> departments(String keyword) throws Exception {
                return guard(() -> client.listDepartments(keyword).stream()
                        .map(dto -> new DepartmentRow(dto.id(), dto.name(), dto.memberCount()))
                        .toList());
            }

            @Override
            public DepartmentRow createDepartment(String name) throws Exception {
                return guard(() -> of(client.createDepartment(name)));
            }

            @Override
            public DepartmentRow renameDepartment(String id, String name) throws Exception {
                return guard(() -> of(client.renameDepartment(id, name)));
            }

            @Override
            public void deleteDepartment(String id) throws Exception {
                guard(() -> {
                    client.deleteDepartment(id);
                    return null;
                });
            }

            @Override
            public Paged<UserRow> users(String keyword, String departmentId, String role,
                                        Boolean enabled, int page, int size) throws Exception {
                return guard(() -> {
                    var dto = client.listUsers(keyword, departmentId, role, enabled, page, size);
                    return new Paged<>(dto.items().stream().map(EnterpriseBridgeImpl::of).toList(),
                            dto.total(), dto.page(), dto.size());
                });
            }

            @Override
            public UserRow createUser(UserDraft draft) throws Exception {
                return guard(() -> of(client.createUser(new EnterpriseApiClient.UserCreate(
                        draft.username(), draft.name(), draft.mobile(), draft.departmentId(),
                        draft.role(), draft.initialPassword()))));
            }

            @Override
            public UserRow updateUser(String id, UserEdit edit) throws Exception {
                return guard(() -> of(client.updateUser(id, new EnterpriseApiClient.UserUpdate(
                        edit.name(), edit.mobile(), edit.departmentId(), edit.role(), edit.enabled()))));
            }

            @Override
            public void deleteUser(String id) throws Exception {
                guard(() -> {
                    client.deleteUser(id);
                    return null;
                });
            }

            @Override
            public List<RoleRow> roles() throws Exception {
                return guard(() -> client.listRoles().stream()
                        .map(dto -> new RoleRow(dto.id(), dto.name(), dto.builtIn(), dto.permissions()))
                        .toList());
            }

            @Override
            public RoleRow createRole(RoleDraft draft) throws Exception {
                return guard(() -> of(client.createRole(new EnterpriseApiClient.RoleSave(
                        draft.name(), draft.permissions()))));
            }

            @Override
            public RoleRow updateRole(String id, RoleDraft draft) throws Exception {
                return guard(() -> of(client.updateRole(id, new EnterpriseApiClient.RoleSave(
                        draft.name(), draft.permissions()))));
            }

            @Override
            public void deleteRole(String id) throws Exception {
                guard(() -> {
                    client.deleteRole(id);
                    return null;
                });
            }

            @Override
            public List<CapabilityRow> capabilities() throws Exception {
                return guard(() -> client.capabilities().stream()
                        .map(dto -> new CapabilityRow(dto.id(), dto.name()))
                        .toList());
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaSummary quotaSummary()
                    throws Exception {
                return guard(() -> {
                    var dto = client.quotaSummary();
                    return new com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaSummary(
                            dto.month(), dto.totalCalls(), dto.totalCostUsd(),
                            dto.departments().stream()
                                    .map(d -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess
                                            .QuotaSummary.DepartmentUsage(d.departmentId(),
                                                    d.departmentName(), d.calls(), d.costUsd(),
                                                    d.monthlyBudgetUsd()))
                                    .toList(),
                            dto.users().stream()
                                    .map(u -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess
                                            .QuotaSummary.UserUsage(u.username(),
                                                    u.departmentName(), u.calls(), u.costUsd(),
                                                    u.monthlyUsd()))
                                    .toList(),
                            dto.models().stream()
                                    .map(m -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess
                                            .QuotaSummary.ModelUsage(m.modelAlias(), m.calls(), m.costUsd()))
                                    .toList());
                });
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits quotaLimits()
                    throws Exception {
                return guard(() -> mapLimits(client.quotaLimits()));
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                    updateQuotaLimits(Double monthlyBudgetUsd, Double monthlyPerUserUsd)
                    throws Exception {
                return guard(() -> mapLimits(
                        client.updateQuotaLimits(monthlyBudgetUsd, monthlyPerUserUsd)));
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                    setDepartmentLimit(String departmentId, Double monthlyUsd) throws Exception {
                return guard(() -> mapLimits(client.setDepartmentLimit(departmentId, monthlyUsd)));
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                    setUserLimit(String username, Double monthlyUsd) throws Exception {
                return guard(() -> mapLimits(client.setUserLimit(username, monthlyUsd)));
            }

            @Override
            public com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                    setModelLimit(String modelAlias, Long monthlyCalls, Double monthlyUsd)
                    throws Exception {
                return guard(() -> mapLimits(
                        client.setModelLimit(modelAlias, monthlyCalls, monthlyUsd)));
            }
        };
    }

    private static com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits mapLimits(
            EnterpriseApiClient.QuotaLimits dto) {
        return new com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits(
                dto.monthlyBudgetUsd(), dto.monthlyPerUserUsd(),
                dto.departments().stream()
                        .map(d -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                                .DepartmentLimit(d.id(), d.name(), d.monthlyUsd()))
                        .toList(),
                dto.userOverrides().stream()
                        .map(u -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                                .UserLimit(u.username(), u.departmentName(), u.monthlyUsd()))
                        .toList(),
                dto.modelLimits().stream()
                        .map(m -> new com.omniforge.ui.enterprise.EnterpriseAdminAccess.QuotaLimits
                                .ModelLimit(m.modelAlias(), m.monthlyCalls(), m.monthlyUsd()))
                        .toList());
    }

    // ---------- 映射 / 异常桥接 ----------

    private static DepartmentRow of(EnterpriseApiClient.DepartmentDto dto) {
        return new DepartmentRow(dto.id(), dto.name(), dto.memberCount());
    }

    private static UserRow of(EnterpriseApiClient.UserDto dto) {
        return new UserRow(dto.id(), dto.username(), dto.name(), dto.mobile(),
                dto.departmentId(), dto.departmentName(), dto.role(), dto.enabled(), dto.createdAt());
    }

    private static RoleRow of(EnterpriseApiClient.RoleDto dto) {
        return new RoleRow(dto.id(), dto.name(), dto.builtIn(), dto.permissions());
    }

    /** 401 → AuthExpired（触发重登框）；403/409/400 的 IllegalStateException 原样上抛（展示服务端中文 message） */
    private <T> T guard(CheckedSupplier<T> supplier) throws Exception {
        try {
            return supplier.get();
        } catch (EnterpriseApiClient.EnterpriseAuthException e) {
            throw new EnterpriseBridge.AuthExpiredException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
