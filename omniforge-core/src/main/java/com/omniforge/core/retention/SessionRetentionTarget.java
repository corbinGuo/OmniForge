package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.repository.DebateRecordRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.ToolCallLogRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话保留目标（DATA_RETENTION Q1-A/Q2-A）：
 * 会话最后活动 = max(消息 createdAt)，无消息回退会话 createdAt；
 * 早于划线 → 连带删除该会话消息、辩论档案与工具调用日志（按 sessionId 级联）。
 */
public class SessionRetentionTarget implements RetentionTarget {

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final DebateRecordRepository debateRecords;
    private final ToolCallLogRepository toolCallLogs;

    public SessionRetentionTarget(SessionRepository sessions, MessageRepository messages,
                                  DebateRecordRepository debateRecords,
                                  ToolCallLogRepository toolCallLogs) {
        this.sessions = sessions;
        this.messages = messages;
        this.debateRecords = debateRecords;
        this.toolCallLogs = toolCallLogs;
    }

    @Override
    public String name() {
        return "session";
    }

    @Override
    @Transactional
    public int cleanup(LocalDateTime before) {
        int removed = 0;
        // 逐会话判定最后活动（会话数有限，全量扫描即可；避免跨实体子查询耦合）
        for (Session session : sessions.findAll()) {
            List<Message> msgs = messages.findBySessionIdOrderByCreatedAtAsc(session.getId());
            LocalDateTime lastActivity = msgs.isEmpty() ? session.getCreatedAt()
                    : msgs.get(msgs.size() - 1).getCreatedAt();
            if (lastActivity != null && lastActivity.isBefore(before)) {
                removed += messages.deleteBySessionId(session.getId());
                removed += debateRecords.deleteBySessionId(session.getId());
                removed += toolCallLogs.deleteBySessionId(session.getId());
                if (sessions.existsById(session.getId())) {
                    sessions.deleteById(session.getId());
                    removed++; // 会话本体
                }
            }
        }
        return removed;
    }
}
