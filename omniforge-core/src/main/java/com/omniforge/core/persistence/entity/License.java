package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** 授权信息（需求 5.1：isPro=true 表示已解锁专业版）。 */
@Entity
@Table(name = "license")
public class License {

    @Id
    @Column(length = 36)
    private String id;

    private String licenseKey;

    private boolean isPro;

    /** 订阅到期日（null = 永久授权）；到期后 isPro 动态判定为 false（自动降级社区版） */
    private LocalDate expiresAt;

    private LocalDateTime activatedAt;

    protected License() {
        this.id = UUID.randomUUID().toString();
    }

    public License(String licenseKey, boolean isPro) {
        this(licenseKey, isPro, null);
    }

    public License(String licenseKey, boolean isPro, LocalDate expiresAt) {
        this();
        this.licenseKey = licenseKey;
        this.isPro = isPro;
        this.expiresAt = expiresAt;
        this.activatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public boolean isPro() {
        return isPro;
    }

    public void setPro(boolean pro) {
        isPro = pro;
    }

    public LocalDateTime getActivatedAt() {
        return activatedAt;
    }

    public LocalDate getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDate expiresAt) {
        this.expiresAt = expiresAt;
    }
}
