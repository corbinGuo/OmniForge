package com.omniforge.core.retention;

/**
 * 数据保留策略设置（DATA_RETENTION Q1-A）：
 * 三类各自可配天数，默认全部<b>关闭</b>（enabled=false 时保留策略不生效）。
 *
 * @param enabled   是否启用保留策略（默认 false，Q1-A 默认关闭）
 * @param chatDays  对话会话保留天数（90；会话最后活动=max(消息 createdAt)，连带删除辩论档案）
 * @param logDays   IM 日志 + 工具调用日志保留天数（90；IM 去重行随日志日龄删 completed 终态）
 * @param auditDays 审计 JSONL 文件保留天数（180）
 */
public record RetentionSettings(boolean enabled, int chatDays, int logDays, int auditDays) {

    /** 默认值：关闭 + 90/90/180（Q1-A） */
    public static RetentionSettings defaults() {
        return new RetentionSettings(false, 90, 90, 180);
    }

    public RetentionSettings {
        chatDays = Math.max(1, chatDays);
        logDays = Math.max(1, logDays);
        auditDays = Math.max(1, auditDays);
    }

    /** 是否启用（便捷谓词） */
    public boolean isEnabled() {
        return enabled;
    }
}
