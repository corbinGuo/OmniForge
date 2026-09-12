package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import com.omniforge.core.persistence.entity.ImMessageDedup;
import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import com.omniforge.core.persistence.repository.ImMessageRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * IM 日志保留目标（DATA_RETENTION Q1-A/Q2-A）：
 * 消息按 receivedAt 早于划线删除；去重行随日志日龄删除——只删 completed 终态，
 * 避免动 processing 在途（幂等去重语义不破）。
 */
public class ImRetentionTarget implements RetentionTarget {

    private final ImMessageRepository imMessages;
    private final ImMessageDedupRepository dedups;

    public ImRetentionTarget(ImMessageRepository imMessages, ImMessageDedupRepository dedups) {
        this.imMessages = imMessages;
        this.dedups = dedups;
    }

    @Override
    public String name() {
        return "im";
    }

    @Override
    @Transactional
    public int cleanup(LocalDateTime before) {
        int removed = 0;
        removed += imMessages.deleteByReceivedAtBefore(before);
        removed += dedups.deleteByStatusAndProcessedAtBefore(ImMessageDedup.STATUS_COMPLETED, before);
        return removed;
    }
}
