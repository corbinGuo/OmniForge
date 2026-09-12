package com.omniforge.core.retention;

import com.omniforge.core.persistence.entity.DebateRecord;
import com.omniforge.core.persistence.entity.ImMessage;
import com.omniforge.core.persistence.entity.ImMessageDedup;
import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.entity.ToolCallLog;
import com.omniforge.core.persistence.entity.Workspace;
import com.omniforge.core.persistence.repository.DebateRecordRepository;
import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import com.omniforge.core.persistence.repository.ImMessageRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.ToolCallLogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据保留清理目标（DATA_RETENTION A3）单测：会话/IM/工具日志/审计各自划线。 */
class RetentionTargetTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
    private final LocalDateTime cutoff = now.minusDays(90);

    // ---------- SessionRetentionTarget ----------

    private SessionRepository sessions() {
        return mock(SessionRepository.class);
    }

    private MessageRepository messages(List<Message> msgs) {
        MessageRepository repo = mock(MessageRepository.class);
        when(repo.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(msgs);
        when(repo.deleteBySessionId(any())).thenReturn((long) msgs.size());
        return repo;
    }

    private Workspace workspace() {
        return new Workspace("工作");
    }

    @Test
    void 会话最后活动早于划线连带删除消息辩论档案与工具日志() {
        SessionRepository sessions = sessions();
        Workspace ws = workspace();
        Session oldSession = new Session(ws, "旧会话", Session.MODE_SINGLE, 1);
        oldSession.setCreatedAt(now.minusDays(200));
        when(sessions.findAll()).thenReturn(List.of(oldSession));
        when(sessions.existsById(oldSession.getId())).thenReturn(true);
        Message oldMsg = new Message(oldSession, "m1", Message.ROLE_USER, "老消息", 10);
        oldMsg.setCreatedAt(now.minusDays(200));
        MessageRepository messages = messages(List.of(oldMsg));
        DebateRecordRepository debates = mock(DebateRecordRepository.class);
        when(debates.deleteBySessionId(oldSession.getId())).thenReturn(1L);
        ToolCallLogRepository toolLogs = mock(ToolCallLogRepository.class);
        when(toolLogs.deleteBySessionId(oldSession.getId())).thenReturn(2L);

        int removed = new SessionRetentionTarget(sessions, messages, debates, toolLogs)
                .cleanup(cutoff);

        assertEquals(1 + 1 + 1 + 2, removed, "消息1+辩论1+工具日志2+会话本体1");
        verify(sessions).deleteById(oldSession.getId());
    }

    @Test
    void 会话消息恰新于划线保留() {
        SessionRepository sessions = sessions();
        Workspace ws = workspace();
        Session fresh = new Session(ws, "新会话", Session.MODE_SINGLE, 1);
        fresh.setCreatedAt(now.minusDays(50));
        when(sessions.findAll()).thenReturn(List.of(fresh));
        Message freshMsg = new Message(fresh, "m1", Message.ROLE_USER, "新消息", 10);
        freshMsg.setCreatedAt(now.minusDays(50));
        MessageRepository messages = messages(List.of(freshMsg));
        DebateRecordRepository debates = mock(DebateRecordRepository.class);
        ToolCallLogRepository toolLogs = mock(ToolCallLogRepository.class);

        int removed = new SessionRetentionTarget(sessions, messages, debates, toolLogs)
                .cleanup(cutoff);

        assertEquals(0, removed);
        verify(sessions, never()).deleteById(any());
    }

    @Test
    void 无消息会话按创建时间判定() {
        SessionRepository sessions = sessions();
        Workspace ws = workspace();
        Session noMsgOld = new Session(ws, "无消息旧会话", Session.MODE_SINGLE, 1);
        noMsgOld.setCreatedAt(now.minusDays(200));
        Session noMsgNew = new Session(ws, "无消息新会话", Session.MODE_SINGLE, 1);
        noMsgNew.setCreatedAt(now.minusDays(10));
        when(sessions.findAll()).thenReturn(List.of(noMsgOld, noMsgNew));
        when(sessions.existsById(noMsgOld.getId())).thenReturn(true);
        MessageRepository messages = messages(List.of());
        DebateRecordRepository debates = mock(DebateRecordRepository.class);
        when(debates.deleteBySessionId(any())).thenReturn(0L);
        ToolCallLogRepository toolLogs = mock(ToolCallLogRepository.class);
        when(toolLogs.deleteBySessionId(any())).thenReturn(0L);

        int removed = new SessionRetentionTarget(sessions, messages, debates, toolLogs)
                .cleanup(cutoff);

        assertEquals(1, removed, "仅无消息旧会话被删");
        verify(sessions).deleteById(noMsgOld.getId());
        verify(sessions, never()).deleteById(noMsgNew.getId());
    }

    // ---------- ImRetentionTarget ----------

    @Test
    void IM老消息删除且completed去重行随日龄删除() {
        ImMessageRepository imMessages = mock(ImMessageRepository.class);
        when(imMessages.deleteByReceivedAtBefore(cutoff)).thenReturn(3L);
        ImMessageDedupRepository dedups = mock(ImMessageDedupRepository.class);
        when(dedups.deleteByStatusAndProcessedAtBefore(ImMessageDedup.STATUS_COMPLETED, cutoff))
                .thenReturn(2L);

        int removed = new ImRetentionTarget(imMessages, dedups).cleanup(cutoff);

        assertEquals(5, removed);
        verify(dedups).deleteByStatusAndProcessedAtBefore(
                ImMessageDedup.STATUS_COMPLETED, cutoff);
    }

    // ---------- ToolLogRetentionTarget ----------

    @Test
    void 工具调用日志按创建时间划线删除() {
        ToolCallLogRepository toolLogs = mock(ToolCallLogRepository.class);
        when(toolLogs.deleteByCreatedAtBefore(cutoff)).thenReturn(4L);
        assertEquals(4, new ToolLogRetentionTarget(toolLogs).cleanup(cutoff));
    }
}
