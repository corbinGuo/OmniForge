package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** 工作区（需求 5.1：如 "工作" / "个人"）。 */
@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    @Column(length = 36)
    private String id;

    private String name;

    private LocalDateTime createdAt;

    protected Workspace() {
        this.id = UUID.randomUUID().toString();
    }

    public Workspace(String name) {
        this();
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
