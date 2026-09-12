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
 * 对话会话（需求 5.1）。
 * mode 取值："single"（单模型）| "debate_judge"（有裁判辩论）| "debate_free"（无裁判辩论）。
 */
@Entity
@Table(name = "session")
public class Session {

    public static final String MODE_SINGLE = "single";
    public static final String MODE_DEBATE_JUDGE = "debate_judge";
    public static final String MODE_DEBATE_FREE = "debate_free";

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    private String name;

    private String mode;

    private int maxRounds;

    private LocalDateTime createdAt;

    protected Session() {
        this.id = UUID.randomUUID().toString();
    }

    public Session(Workspace workspace, String name, String mode, int maxRounds) {
        this();
        this.workspace = workspace;
        this.name = name;
        this.mode = mode;
        this.maxRounds = maxRounds;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
