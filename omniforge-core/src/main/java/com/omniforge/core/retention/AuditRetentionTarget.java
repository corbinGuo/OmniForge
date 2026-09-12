package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import com.omniforge.core.audit.AuditLogService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 审计文件保留目标（DATA_RETENTION Q1-A/Q2-A）：删除文件名日期早于划线的 JSONL。 */
public class AuditRetentionTarget implements RetentionTarget {

    private final AuditLogService auditLogService;

    public AuditRetentionTarget(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public int cleanup(LocalDateTime before) {
        return auditLogService.cleanupBefore(before.toInstant(ZoneOffset.UTC));
    }
}
