package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 辩论记录（2.7）：一场辩论的完整归档。
 *
 * <p>复用 Session/Message 表存逐条消息（role=assistant/judge），
 * 本表存汇总与逐轮数据（rounds_json），支撑 Markdown/HTML 导出与历史重播。</p>
 */
@Entity
@Table(name = "debate_record")
public class DebateRecord {

    @Id
    @Column(length = 36)
    private String id;

    /** 关联会话 ID（Session 表，复用） */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** 辩论主题 */
    private String topic;

    /** 模式：debate_free（无裁判）/ debate_judge（有裁判） */
    private String mode;

    /** 参与模型别名（逗号分隔，顺序即展示顺序） */
    @Column(name = "model_aliases", columnDefinition = "TEXT")
    private String modelAliases;

    /** 裁判模型别名（无裁判模式为 null） */
    @Column(name = "judge_alias")
    private String judgeAlias;

    @Column(name = "max_rounds")
    private int maxRounds;

    /** 终止原因（StopReason 枚举名） */
    @Column(name = "stop_reason")
    private String stopReason;

    /** 获胜方别名（仅裁判宣布获胜方时非空） */
    @Column(name = "winner_alias")
    private String winnerAlias;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private long durationMs;

    /** 逐轮数据 JSON（round/completedAt/outputs/judgeVerdict），导出与重播的数据源 */
    @Column(name = "rounds_json", columnDefinition = "TEXT")
    private String roundsJson;

    protected DebateRecord() {
        this.id = UUID.randomUUID().toString();
    }

    public DebateRecord(String sessionId, String topic, String mode, String modelAliases,
                        String judgeAlias, int maxRounds, String stopReason, String winnerAlias,
                        LocalDateTime startedAt, LocalDateTime finishedAt, long durationMs,
                        String roundsJson) {
        this();
        this.sessionId = sessionId;
        this.topic = topic;
        this.mode = mode;
        this.modelAliases = modelAliases;
        this.judgeAlias = judgeAlias;
        this.maxRounds = maxRounds;
        this.stopReason = stopReason;
        this.winnerAlias = winnerAlias;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMs = durationMs;
        this.roundsJson = roundsJson;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMode() {
        return mode;
    }

    public String getModelAliases() {
        return modelAliases;
    }

    public String getJudgeAlias() {
        return judgeAlias;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public String getStopReason() {
        return stopReason;
    }

    public String getWinnerAlias() {
        return winnerAlias;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getRoundsJson() {
        return roundsJson;
    }
}
