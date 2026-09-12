package com.omniforge.core.persistence.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omniforge.core.debate.DebateResult;
import com.omniforge.core.persistence.entity.DebateRecord;
import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.entity.Workspace;
import com.omniforge.core.persistence.repository.DebateRecordRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.WorkspaceRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 辩论记录服务（2.7）：
 * <ul>
 *   <li>落库：复用 Session/Message 表（逐条消息：辩手=assistant、裁判=judge）+ DebateRecord 归档（逐轮汇总/裁判评语/熔断原因）；</li>
 *   <li>导出：Markdown（按角色/模型分块、轮次标记、时间戳）/ HTML（基础可读样式）；</li>
 *   <li>重播：按时间顺序输出逐条条目（轮次分隔 → 各模型发言 → 裁判评语）。</li>
 * </ul>
 */
public class DebateRecordService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_WORKSPACE = "默认";

    private final DebateRecordRepository recordRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ObjectMapper objectMapper;

    public DebateRecordService(DebateRecordRepository recordRepository, SessionRepository sessionRepository,
                               MessageRepository messageRepository, WorkspaceRepository workspaceRepository) {
        this.recordRepository = recordRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 归档一场辩论：Session + 逐条 Message + DebateRecord（一次事务） */
    @Transactional
    public DebateRecord save(DebateResult result) {
        Objects.requireNonNull(result, "result");
        Workspace workspace = workspaceRepository.findByName(DEFAULT_WORKSPACE)
                .orElseGet(() -> workspaceRepository.save(new Workspace(DEFAULT_WORKSPACE)));
        String mode = result.judgeAlias() == null ? Session.MODE_DEBATE_FREE : Session.MODE_DEBATE_JUDGE;
        String recordMode = result.judgeAlias() != null
                ? "debate_judge"
                : (result.discussionMode() == null ? "debate_free" : result.discussionMode());
        Session session = sessionRepository.save(
                new Session(workspace, "辩论：" + result.topic(), mode, result.maxRounds()));

        // 逐条消息落库（时间戳 = 轮次完成时间，重播按时间排序）
        for (var round : result.rounds()) {
            for (Map.Entry<String, String> entry : round.modelTexts().entrySet()) {
                Message message = new Message(session, entry.getKey(), Message.ROLE_ASSISTANT,
                        entry.getValue(), estimateTokens(entry.getValue()));
                message.setCreatedAt(round.completedAt());
                messageRepository.save(message);
            }
        }
        for (var verdict : result.judgeVerdicts()) {
            Message message = new Message(session, result.judgeAlias(), Message.ROLE_JUDGE,
                    verdict.verdict(), estimateTokens(verdict.verdict()));
            message.setCreatedAt(result.rounds().stream()
                    .filter(r -> r.round() == verdict.round())
                    .findFirst()
                    .map(r -> r.completedAt())
                    .orElse(LocalDateTime.now()));
            messageRepository.save(message);
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        DebateRecord record = new DebateRecord(
                session.getId(), result.topic(), recordMode, String.join(",", result.modelAliases()),
                result.judgeAlias(), result.maxRounds(), result.stopReason().name(),
                result.winnerAlias(), finishedAt.minusNanos(result.durationMs() * 1_000_000L),
                finishedAt, result.durationMs(), serializeRounds(result));
        return recordRepository.save(record);
    }

    /** 全部记录（时间倒序，历史列表） */
    public List<DebateRecord> listRecords() {
        return recordRepository.findAllByOrderByStartedAtDesc();
    }

    /** 按 ID 查找 */
    public DebateRecord find(String recordId) {
        return recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("辩论记录不存在: " + recordId));
    }

    /** Markdown 导出：按角色/模型分块，含轮次标记与时间戳 */
    public String exportMarkdown(DebateRecord record) {
        List<DebateRoundData> rounds = parseRounds(record.getRoundsJson());
        StringBuilder md = new StringBuilder();
        md.append("# 辩论记录\n\n");
        md.append("- 主题：").append(record.getTopic()).append('\n');
        md.append("- 模式：").append(record.getJudgeAlias() == null ? "无裁判" : "有裁判（" + record.getJudgeAlias() + "）").append('\n');
        md.append("- 参与模型：").append(record.getModelAliases()).append('\n');
        md.append("- 最大轮次：").append(record.getMaxRounds()).append('\n');
        md.append("- 结果：").append(record.getStopReason())
                .append(record.getWinnerAlias() == null ? "" : "，获胜方：" + record.getWinnerAlias()).append('\n');
        md.append("- 开始时间：").append(format(record.getStartedAt()))
                .append("（耗时 ").append(record.getDurationMs()).append(" ms）\n\n");
        for (DebateRoundData round : rounds) {
            md.append("## 第 ").append(round.round()).append(" 轮（")
                    .append(format(round.completedAt())).append("）\n\n");
            for (Map.Entry<String, String> entry : round.outputs().entrySet()) {
                md.append("### ").append(entry.getKey()).append("\n\n")
                        .append(entry.getValue()).append("\n\n");
            }
            if (round.judgeVerdict() != null) {
                md.append("**⚖ 裁判评语：**\n\n").append(round.judgeVerdict()).append("\n\n");
            }
        }
        return md.toString();
    }

    /** HTML 导出（基础可读样式） */
    public String exportHtml(DebateRecord record) {
        List<DebateRoundData> rounds = parseRounds(record.getRoundsJson());
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"zh\"><head><meta charset=\"UTF-8\">")
                .append("<title>辩论记录</title>")
                .append("<style>body{font-family:sans-serif;max-width:900px;margin:24px auto;line-height:1.7}")
                .append("h1{color:#2563EB}h2{border-bottom:1px solid #ddd;padding-bottom:4px}")
                .append("h3{color:#333;margin-bottom:4px}pre{white-space:pre-wrap;background:#f7f7f8;padding:10px;border-radius:8px}")
                .append(".judge{background:#eef2ff;padding:10px;border-radius:8px}</style></head><body>\n");
        html.append("<h1>辩论记录</h1>\n");
        html.append("<p>主题：").append(escapeHtml(record.getTopic())).append("</p>\n");
        html.append("<p>参与模型：").append(escapeHtml(record.getModelAliases()))
                .append(" ｜ 结果：").append(escapeHtml(record.getStopReason())).append("</p>\n");
        for (DebateRoundData round : rounds) {
            html.append("<h2>第 ").append(round.round()).append(" 轮（")
                    .append(format(round.completedAt())).append("）</h2>\n");
            for (Map.Entry<String, String> entry : round.outputs().entrySet()) {
                html.append("<h3>").append(escapeHtml(entry.getKey())).append("</h3>\n")
                        .append("<pre>").append(escapeHtml(entry.getValue())).append("</pre>\n");
            }
            if (round.judgeVerdict() != null) {
                html.append("<div class=\"judge\"><strong>⚖ 裁判评语：</strong><br>")
                        .append(escapeHtml(round.judgeVerdict())).append("</div>\n");
            }
        }
        html.append("</body></html>\n");
        return html.toString();
    }

    /** 重播条目（按时间顺序：轮次分隔 → 各模型发言 → 裁判评语） */
    public record ReplayItem(int seq, int round, String role, String alias, String text, LocalDateTime at) {
    }

    /** 历史重播：基于落库的 DebateRecord，按时间顺序逐条重现 */
    public List<ReplayItem> replay(DebateRecord record) {
        List<ReplayItem> items = new ArrayList<>();
        int seq = 0;
        for (DebateRoundData round : parseRounds(record.getRoundsJson())) {
            items.add(new ReplayItem(seq++, round.round(), "system", null,
                    "—— 第 " + round.round() + " 轮（" + format(round.completedAt()) + "）——",
                    round.completedAt()));
            for (Map.Entry<String, String> entry : round.outputs().entrySet()) {
                items.add(new ReplayItem(seq++, round.round(), "assistant", entry.getKey(),
                        entry.getValue(), round.completedAt()));
            }
            if (round.judgeVerdict() != null) {
                items.add(new ReplayItem(seq++, round.round(), "judge", record.getJudgeAlias(),
                        round.judgeVerdict(), round.completedAt()));
            }
        }
        return items;
    }

    private String serializeRounds(DebateResult result) {
        List<DebateRoundData> rounds = result.rounds().stream()
                .map(r -> new DebateRoundData(r.round(), r.completedAt(), r.modelTexts(),
                        result.judgeVerdicts().stream()
                                .filter(v -> v.round() == r.round())
                                .findFirst()
                                .map(DebateResult.JudgeVerdictEntry::verdict)
                                .orElse(null)))
                .toList();
        try {
            return objectMapper.writeValueAsString(rounds);
        } catch (Exception e) {
            throw new IllegalStateException("辩论记录序列化失败", e);
        }
    }

    private List<DebateRoundData> parseRounds(String roundsJson) {
        if (roundsJson == null || roundsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(roundsJson, new TypeReference<List<DebateRoundData>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 2);
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : TIME_FORMAT.format(time);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
