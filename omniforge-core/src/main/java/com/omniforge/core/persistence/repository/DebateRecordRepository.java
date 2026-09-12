package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.DebateRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 辩论记录仓库（2.7 历史重播的数据源）。 */
public interface DebateRecordRepository extends JpaRepository<DebateRecord, String> {

    List<DebateRecord> findAllByOrderByStartedAtDesc();

    List<DebateRecord> findBySessionIdOrderByStartedAtAsc(String sessionId);

    /** 按会话删除（保留策略 SessionRetentionTarget 级联清理；返回删除条数） */
    long deleteBySessionId(String sessionId);
}
