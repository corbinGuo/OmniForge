package com.omniforge.ui.enterprise;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业版管理访问契约（P2-2，ui 侧中立 DTO）：ui 不依赖 app，
 * 由 app 装配层实现（EnterpriseBridgeImpl 包 EnterpriseApiClient），
 * 服务端对 /api/admin/* 强制 role=admin（403 兜底）。
 */
public interface EnterpriseAdminAccess {

    record Paged<T>(List<T> items, long total, int page, int size) {
    }

    record DepartmentRow(String id, String name, long memberCount) {
    }

    record UserRow(String id, String username, String name, String mobile,
                   String departmentId, String departmentName, String role,
                   boolean enabled, LocalDateTime createdAt) {
    }

    record RoleRow(String id, String name, boolean builtIn, List<String> permissions) {
    }

    record CapabilityRow(String id, String name) {
    }

    record UserDraft(String username, String name, String mobile,
                     String departmentId, String role, String initialPassword) {
    }

    record UserEdit(String name, String mobile, String departmentId,
                    String role, Boolean enabled) {
    }

    record RoleDraft(String name, List<String> permissions) {
    }

    // ---- 部门 ----

    List<DepartmentRow> departments(String keyword) throws Exception;

    DepartmentRow createDepartment(String name) throws Exception;

    DepartmentRow renameDepartment(String id, String name) throws Exception;

    void deleteDepartment(String id) throws Exception;

    // ---- 用户 ----

    Paged<UserRow> users(String keyword, String departmentId, String role,
                         Boolean enabled, int page, int size) throws Exception;

    UserRow createUser(UserDraft draft) throws Exception;

    UserRow updateUser(String id, UserEdit edit) throws Exception;

    void deleteUser(String id) throws Exception;

    // ---- 角色 / 能力 ----

    List<RoleRow> roles() throws Exception;

    RoleRow createRole(RoleDraft draft) throws Exception;

    RoleRow updateRole(String id, RoleDraft draft) throws Exception;

    void deleteRole(String id) throws Exception;

    List<CapabilityRow> capabilities() throws Exception;

    // ---- 用量与配额（ENTERPRISE_METERING） ----

    record QuotaSummary(String month, long totalCalls, double totalCostUsd,
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

    record QuotaLimits(Double monthlyBudgetUsd, Double monthlyPerUserUsd,
                       List<DepartmentLimit> departments, List<UserLimit> userOverrides,
                       List<ModelLimit> modelLimits) {
        public record DepartmentLimit(String id, String name, Double monthlyUsd) {
        }
        public record UserLimit(String username, String departmentName, Double monthlyUsd) {
        }
        public record ModelLimit(String modelAlias, Long monthlyCalls, Double monthlyUsd) {
        }
    }

    QuotaSummary quotaSummary() throws Exception;

    QuotaLimits quotaLimits() throws Exception;

    /** 企业默认限额（null=不限） */
    QuotaLimits updateQuotaLimits(Double monthlyBudgetUsd, Double monthlyPerUserUsd) throws Exception;

    /** 部门限额（null=清除） */
    QuotaLimits setDepartmentLimit(String departmentId, Double monthlyUsd) throws Exception;

    /** 用户限额（null=删除覆写回企业默认） */
    QuotaLimits setUserLimit(String username, Double monthlyUsd) throws Exception;

    /** 模型级月限额（calls/usd 全 null = 清除该行限额） */
    QuotaLimits setModelLimit(String modelAlias, Long monthlyCalls, Double monthlyUsd) throws Exception;
}
