package com.omniforge.ui.enterprise;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 企业版文档产出（D5）Markdown 合成器：
 * 把「一个会话的对话消息 + 该会话内发起的多模型协作」整理成一份 Markdown 草稿。
 *
 * <p>详略约定（Q3 摘要+全文）：会话消息全文；协作段——讨论取 transcript 前 30 行摘要，
 * 结论/执行结果/验收评语全文；MD 开头含元信息（会话名/导出时间/消息数/协作数）。</p>
 *
 * <p>纯静态、无 JavaFX 依赖：输入用中性 record，便于单测。</p>
 */
public final class DocDraftBuilder {

    /** 会话消息（时间正序，与 GET /api/sessions/{id}/messages 一致） */
    public record ChatLine(String role, String content, LocalDateTime createdAt) {
    }

    /** 协作运行快照 + 讨论全文（transcript 由调用方另行拉取并入） */
    public record CollabRun(String id, String mode, String topic, List<String> aliases,
                            String judgeAlias, int maxRounds, String status,
                            String conclusion, String executionOutput, String reviewVerdict,
                            String reviewReason, String transcript, String createdAt) {
    }

    /** 讨论摘要行数上限（Q3：前 30 行） */
    private static final int TRANSCRIPT_HEAD_LINES = 30;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private DocDraftBuilder() {
    }

    /**
     * 合成 Markdown 草稿：消息与协作按时间正序穿插。
     *
     * @param sessionName 会话名（服务端首条消息前 30 字，可能为空）
     * @param sessionId   会话 ID（用于元信息与默认文件名）
     * @param messages    会话消息（时间正序）
     * @param collabs     该会话内发起的协作（可为空）
     */
    public static String build(String sessionName, String sessionId,
                               List<ChatLine> messages, List<CollabRun> collabs) {
        List<Object> items = new ArrayList<>();
        if (messages != null) {
            for (ChatLine line : messages) {
                if (line.content() != null && !line.content().isBlank()) {
                    items.add(line);
                }
            }
        }
        if (collabs != null) {
            items.addAll(collabs);
        }
        // 按时间正序穿插（消息取 createdAt；协作取快照 createdAt，解析失败按最早排）
        items.sort(Comparator.comparing(DocDraftBuilder::sortKey));

        StringBuilder sb = new StringBuilder();
        String title = sessionName == null || sessionName.isBlank() ? "会话文档" : sessionName;
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 会话 ID：").append(sessionId == null ? "" : sessionId).append("\n");
        sb.append("> 导出时间：").append(LocalDateTime.now().format(TIME)).append("\n");
        sb.append("> 对话 ").append(count(messages)).append(" 条 · 多模型协作 ")
                .append(collabs == null ? 0 : collabs.size()).append(" 场\n\n");

        for (Object item : items) {
            if (item instanceof ChatLine line) {
                sb.append(renderMessage(line)).append("\n");
            } else if (item instanceof CollabRun run) {
                sb.append(renderCollab(run)).append("\n");
            }
        }
        return sb.toString().trim() + "\n";
    }

    private static String renderMessage(ChatLine line) {
        String role = switch (line.role() == null ? "" : line.role().toLowerCase(Locale.ROOT)) {
            case "user" -> "🧑 用户";
            case "assistant" -> "🤖 助手";
            case "system" -> "📝 系统";
            default -> "📝 " + line.role();
        };
        return "## " + role + " · " + formatTime(line.createdAt()) + "\n\n"
                + line.content().strip() + "\n";
    }

    private static String renderCollab(CollabRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🧠 多模型协作：").append(blankTo(run.topic(), "（无主题）")).append("\n\n");
        sb.append("- 模式：").append(modeLabel(run.mode())).append("\n");
        sb.append("- 参与模型：").append(run.aliases() == null || run.aliases().isEmpty()
                ? "（无）" : String.join(" + ", run.aliases())).append("\n");
        if (run.judgeAlias() != null && !run.judgeAlias().isBlank()) {
            sb.append("- 裁判：").append(run.judgeAlias()).append("\n");
        }
        sb.append("- 轮次：").append(run.maxRounds()).append("\n");
        sb.append("- 状态：").append(statusLabel(run.status())).append("\n");
        sb.append("- 时间：").append(blankTo(run.createdAt(), "（未知）")).append("\n");

        sb.append("\n### 讨论摘要\n\n")
                .append(transcriptHead(run.transcript()))
                .append("\n");
        sb.append("\n### 结论\n\n")
                .append(blankTo(run.conclusion(), "（尚未生成）"))
                .append("\n");
        sb.append("\n### 执行结果\n\n")
                .append(blankTo(run.executionOutput(), "（尚未执行）"))
                .append("\n");
        sb.append("\n### 验收评语\n\n")
                .append(reviewLabel(run.reviewVerdict()))
                .append(blankTo(run.reviewReason(), "（尚未验收）"))
                .append("\n");
        return sb.toString();
    }

    /** 讨论摘要：transcript 前 30 行（Q3） */
    private static String transcriptHead(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return "（无讨论记录）";
        }
        String[] lines = transcript.strip().split("\n", -1);
        int head = Math.min(lines.length, TRANSCRIPT_HEAD_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head; i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > head) {
            sb.append("…（共 ").append(lines.length).append(" 行，此处仅摘要）\n");
        }
        return sb.toString().strip();
    }

    private static String reviewLabel(String verdict) {
        if ("pass".equalsIgnoreCase(verdict)) {
            return "✅ 通过\n\n";
        }
        if ("revise".equalsIgnoreCase(verdict)) {
            return "⚠ 未通过（需重试）\n\n";
        }
        return "";
    }

    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "debate" -> "正反辩论";
            case "discussion" -> "圆桌讨论";
            case "brainstorm" -> "发散头脑风暴";
            default -> mode == null || mode.isBlank() ? "（未知）" : mode;
        };
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "done" -> "已完成";
            case "revised" -> "待重试";
            case "aborted" -> "中止";
            default -> status == null || status.isBlank() ? "（未知）" : status;
        };
    }

    private static LocalDateTime sortKey(Object item) {
        if (item instanceof ChatLine line) {
            return line.createdAt() == null ? LocalDateTime.MIN : line.createdAt();
        }
        if (item instanceof CollabRun run) {
            return parseSessionTime(run.createdAt());
        }
        return LocalDateTime.MIN;
    }

    /** 服务端 RunDto.createdAt 为 "yyyy-MM-dd HH:mm" 文本；解析失败按最早 */
    private static LocalDateTime parseSessionTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.MIN;
        }
        try {
            return LocalDateTime.parse(raw, SESSION_TIME);
        } catch (Exception e) {
            return LocalDateTime.MIN;
        }
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "（未知）" : time.format(TIME);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int count(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
