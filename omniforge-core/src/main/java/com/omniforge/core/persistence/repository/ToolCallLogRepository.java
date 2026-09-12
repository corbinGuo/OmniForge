package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 工具调用日志仓库。 */
public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, String> {

    List<ToolCallLog> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ToolCallLog> findByMessageId(String messageId);

    /** 删除创建时间早于划线的日志（保留策略 ToolLogRetentionTarget；返回删除条数） */
    long deleteByCreatedAtBefore(java.time.LocalDateTime before);

    /** 按会话删除（会话清理级联；返回删除条数） */
    long deleteBySessionId(String sessionId);
}
