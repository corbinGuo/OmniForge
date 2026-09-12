package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.ImMessageDedup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** IM 消息幂等去重仓库（(messageId, platform) 复合唯一，v5.2.1 决议方案 B）。 */
public interface ImMessageDedupRepository extends JpaRepository<ImMessageDedup, String> {

    Optional<ImMessageDedup> findByMessageIdAndPlatform(String messageId, String platform);

    /** 删除处理时间早于划线且为 completed 终态的去重行（保留策略；processing 在途不动） */
    long deleteByStatusAndProcessedAtBefore(String status, java.time.LocalDateTime before);
}
