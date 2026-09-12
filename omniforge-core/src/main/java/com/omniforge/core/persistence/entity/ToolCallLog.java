package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工具调用日志（需求 5.1）。
 * status 取值："pending" | "success" | "failed" | "timeout"。
 * message 为冗余外键（session 已可追溯），保留用于按消息直查。
 */
@Entity
@Table(name = "tool_call_log")
public class ToolCallLog {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_TIMEOUT = "timeout";

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id")
    private Session session;

    @ManyToOne
    @JoinColumn(name = "message_id")
    private Message message;

    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String inputParams;

    @Column(columnDefinition = "TEXT")
    private String outputResult;

    private String status;

    private int durationMs;

    private LocalDateTime createdAt;

    protected ToolCallLog() {
        this.id = UUID.randomUUID().toString();
    }

    public ToolCallLog(Session session, Message message, String toolName, String inputParams,
                       String outputResult, String status, int durationMs) {
        this();
        this.session = session;
        this.message = message;
        this.toolName = toolName;
        this.inputParams = inputParams;
        this.outputResult = outputResult;
        this.status = status;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public Session getSession() {
        return session;
    }

    public Message getMessage() {
        return message;
    }

    public String getToolName() {
        return toolName;
    }

    public String getInputParams() {
        return inputParams;
    }

    public String getOutputResult() {
        return outputResult;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
