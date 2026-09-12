package com.omniforge.core.persistence.service;

import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.entity.Workspace;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.WorkspaceRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 对话历史会话服务（UI 历史列表侧栏的数据层）。
 *
 * <p>复用 Session（mode=single）/Message 两表：普通对话逐轮落库，
 * 会话标题取首条用户消息前 30 字（可重命名）；列表按最后活动时间倒序，
 * 分组（今天/昨天/本周/更早）由 UI 侧按 {@link ChatSessionInfo#updatedAt()} 计算。</p>
 */
public class ChatSessionService {

    /** 与辩论记录共用默认工作区（UI 对话历史与辩论记录同库） */
    private static final String DEFAULT_WORKSPACE = "默认";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;

    public ChatSessionService(SessionRepository sessionRepository,
                              MessageRepository messageRepository,
                              WorkspaceRepository workspaceRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /** 会话摘要（历史列表展示用） */
    public record ChatSessionInfo(String id, String name, LocalDateTime updatedAt, int messageCount) {
    }

    /** 全部会话（按最后活动倒序；messageCount=消息条数） */
    public List<ChatSessionInfo> list() {
        Workspace workspace = workspaceRepository.findByName(DEFAULT_WORKSPACE).orElse(null);
        if (workspace == null) {
            return List.of();
        }
        List<ChatSessionInfo> result = new ArrayList<>();
        for (Session session : sessionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
            LocalDateTime updatedAt = messages.isEmpty()
                    ? session.getCreatedAt()
                    : messages.get(messages.size() - 1).getCreatedAt();
            result.add(new ChatSessionInfo(session.getId(), session.getName(), updatedAt, messages.size()));
        }
        // 稳定排序：按最后活动（updatedAt = 最后一条消息时间）倒序；同毫秒时保留创建先后
        // （此前按会话 createdAt 排序，同毫秒创建会顺序抖动——2026-09 修复，见测试 列表按最后活动排序）
        result.sort(Comparator.comparing(ChatSessionInfo::updatedAt).reversed());
        return result;
    }

    /** 会话消息（时间正序，UI 恢复会话渲染用） */
    public List<Message> messages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 追加一轮对话（user + assistant 成对落库，一次事务）。
     * 会话不存在时自动创建，标题取首条用户消息前 30 字。
     *
     * @return 会话 ID（新建或既有）
     */
    @Transactional
    public String appendTurn(String sessionId, String userText, String assistantText, String modelAlias) {
        Session session = sessionId == null ? null : sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            Workspace workspace = workspaceRepository.findByName(DEFAULT_WORKSPACE)
                    .orElseGet(() -> workspaceRepository.save(new Workspace(DEFAULT_WORKSPACE)));
            session = sessionRepository.save(new Session(workspace, titleOf(userText),
                    Session.MODE_SINGLE, 1));
        }
        messageRepository.save(new Message(session, modelAlias, Message.ROLE_USER, userText,
                estimateTokens(userText)));
        messageRepository.save(new Message(session, modelAlias, Message.ROLE_ASSISTANT, assistantText,
                estimateTokens(assistantText)));
        return session.getId();
    }

    /** 重命名会话 */
    @Transactional
    public void rename(String sessionId, String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("会话名称不能为空");
        }
        Session session = requireSession(sessionId);
        session.setName(name.trim());
        sessionRepository.save(session);
    }

    /** 删除会话及其全部消息 */
    @Transactional
    public void delete(String sessionId) {
        if (sessionRepository.existsById(sessionId)) {
            messageRepository.deleteBySessionId(sessionId);
            sessionRepository.deleteById(sessionId);
        }
    }

    /** 导出会话为 Markdown（含标题与逐条消息时间戳） */
    public String exportMarkdown(String sessionId) {
        Session session = requireSession(sessionId);
        List<Message> messages = messages(sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.getName()).append("\n\n");
        for (Message message : messages) {
            String role = switch (message.getRole()) {
                case Message.ROLE_USER -> "🧑 用户";
                case Message.ROLE_ASSISTANT -> "🤖 助手" + (message.getModelName() == null
                        ? "" : "（" + message.getModelName() + "）");
                default -> "📝 " + message.getRole();
            };
            sb.append("## ").append(role).append(" · ")
                    .append(TIME_FORMAT.format(message.getCreatedAt())).append("\n\n")
                    .append(message.getContent()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private Session requireSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    private static String titleOf(String userText) {
        String normalized = userText == null ? "" : userText.replace('\n', ' ').trim();
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30) + "…";
    }

    /** 粗略 token 估算（CJK 1 字 ≈ 1 token，拉丁 4 字符 ≈ 1 token；仅用于消息表统计展示） */
    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                latin++;
            }
        }
        return cjk + latin / 4 + 1;
    }
}
