package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import com.omniforge.core.persistence.repository.ToolCallLogRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 工具调用日志保留目标（DATA_RETENTION Q1-A/Q2-A）：按 createdAt 划线删除。 */
public class ToolLogRetentionTarget implements RetentionTarget {

    private final ToolCallLogRepository toolCallLogs;

    public ToolLogRetentionTarget(ToolCallLogRepository toolCallLogs) {
        this.toolCallLogs = toolCallLogs;
    }

    @Override
    public String name() {
        return "tool-log";
    }

    @Override
    @Transactional
    public int cleanup(LocalDateTime before) {
        return (int) toolCallLogs.deleteByCreatedAtBefore(before);
    }
}
