package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.ImMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** IM 收件消息日志仓库（Phase 3 Step 2）。 */
public interface ImMessageRepository extends JpaRepository<ImMessage, String> {

    List<ImMessage> findByPlatformOrderByReceivedAtDesc(String platform);

    /** 删除接收时间早于划线的日志（保留策略 ImRetentionTarget；返回删除条数） */
    long deleteByReceivedAtBefore(java.time.LocalDateTime before);
}
