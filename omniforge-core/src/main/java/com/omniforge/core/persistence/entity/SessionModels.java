package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 辩论参与模型列表（v5.1 决议 #6）。
 *
 * <p>用途：无裁判模式 = 所有 role=debater 的模型参与自由辩论；
 * 有裁判模式 = role=debater + 一个 role=judge 的裁判模型。</p>
 */
@Entity
@Table(name = "session_models",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_models_session_model",
                columnNames = {"session_id", "model_name"}))
public class SessionModels {

    public static final String ROLE_DEBATER = "debater";
    public static final String ROLE_JUDGE = "judge";

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id")
    private Session session;

    /** 参与辩论的模型名称（模型别名） */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** "debater" | "judge"（仅裁判模式） */
    private String role;

    /** 显示顺序 */
    private Integer displayOrder;

    protected SessionModels() {
        this.id = UUID.randomUUID().toString();
    }

    public SessionModels(Session session, String modelName, String role, Integer displayOrder) {
        this();
        this.session = session;
        this.modelName = modelName;
        this.role = role;
        this.displayOrder = displayOrder;
    }

    public String getId() {
        return id;
    }

    public Session getSession() {
        return session;
    }

    public String getModelName() {
        return modelName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
}
