package com.omniforge.core.retention;

/**
 * 主库 Schema 代际标记（DATA_RETENTION A4）。
 *
 * <p><b>纪律：任何主库 schema 变更的提交必须 +1（Javadoc 注明）</b>——
 * 启动时 {@link BackupService} 比对本常量与落盘标记（{@code .schema-version}），
 * 不一致即视为升级 → 自动备份旧库。向量库 ALTER 类变更如跟随版本发布同样 +1
 * （两库共用一标记，简单可靠）。</p>
 */
public final class AppSchema {

    /**
     * 当前主库 schema 代际；主库结构变更时必须递增。
     * <ul>
     *   <li>1 → 2（2026-09-12）：license 表加 expiresAt 列（License v2 订阅有效期）</li>
     * </ul>
     */
    private static final int CURRENT_GENERATION = 2;

    private AppSchema() {
    }

    /** 当前 schema 代际 */
    public static int schemaGeneration() {
        return CURRENT_GENERATION;
    }
}
