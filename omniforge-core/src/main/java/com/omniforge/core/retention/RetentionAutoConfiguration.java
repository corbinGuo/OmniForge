package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import com.omniforge.core.persistence.PersistenceProperties;
import com.omniforge.core.persistence.repository.DebateRecordRepository;
import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import com.omniforge.core.persistence.repository.ImMessageRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.ToolCallLogRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;

/**
 * 数据保留与升级备份自动装配（DATA_RETENTION A3/A4）：
 * 保留设置（retention.yml + Holder 热生效）、四类清理目标、每日调度服务、
 * 升级自动备份（BackupService + 成功启动回写标记）。企业版（PG）不装配本模块。
 */
@AutoConfiguration(afterName = {
        "com.omniforge.core.persistence.PersistenceAutoConfiguration",
        "com.omniforge.core.audit.AuditAutoConfiguration"})
@ConditionalOnClass({SessionRepository.class, PersistenceProperties.class})
public class RetentionAutoConfiguration {

    /** 配置目录 = 主库文件父目录；:memory: 测试库无真实路径 → 回退临时目录 */
    static Path configDir(PersistenceProperties properties) {
        if (properties.isInMemory()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "omniforge-retention-test");
        }
        return Path.of(properties.getDatabaseFile()).toAbsolutePath().getParent();
    }

    /** 保留策略文件（配置目录下） */
    static Path retentionFile(PersistenceProperties properties) {
        return configDir(properties).resolve("retention.yml");
    }

    /** 向量库路径：显式配置优先，缺失回退配置目录 vectors.db（与知识库默认一致） */
    static Path vectorsFile(PersistenceProperties properties, Environment environment) {
        String custom = environment.getProperty("omniforge.knowledge.vector-db-file");
        return custom == null || custom.isBlank()
                ? configDir(properties).resolve("vectors.db") : Path.of(custom);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetentionSettingsStore retentionSettingsStore() {
        return new RetentionSettingsStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetentionSettingsHolder retentionSettingsHolder(RetentionSettingsStore store,
                                                           PersistenceProperties properties) {
        return new RetentionSettingsHolder(store.load(retentionFile(properties)));
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionRetentionTarget sessionRetentionTarget(SessionRepository sessions,
                                                         MessageRepository messages,
                                                         DebateRecordRepository debateRecords,
                                                         ToolCallLogRepository toolCallLogs) {
        return new SessionRetentionTarget(sessions, messages, debateRecords, toolCallLogs);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImRetentionTarget imRetentionTarget(ImMessageRepository imMessages,
                                               ImMessageDedupRepository dedups) {
        return new ImRetentionTarget(imMessages, dedups);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolLogRetentionTarget toolLogRetentionTarget(ToolCallLogRepository toolCallLogs) {
        return new ToolLogRetentionTarget(toolCallLogs);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditRetentionTarget auditRetentionTarget(
            ObjectProvider<com.omniforge.core.audit.AuditLogService> auditLogs) {
        // 审计未装配（enabled=false）时 target 传入 null → cleanup 恒 0，不报错
        return new AuditRetentionTarget(auditLogs.getIfAvailable(() -> null));
    }

    @Bean
    @ConditionalOnMissingBean
    public DataRetentionService dataRetentionService(RetentionSettingsHolder settingsHolder,
                                                     List<RetentionTarget> targets) {
        return new DataRetentionService(settingsHolder, targets);
    }

    @Bean
    @ConditionalOnMissingBean
    public BackupService backupService(PersistenceProperties properties, Environment environment) {
        // :memory: 测试库不可备份（Windows 上冒号也无法作路径）→ null 表示无库
        Path db = properties.isInMemory() ? null : Path.of(properties.getDatabaseFile());
        return new BackupService(db, vectorsFile(properties, environment));
    }

    @Bean
    @ConditionalOnMissingBean
    public BackupMarkerWriter backupMarkerWriter(BackupService backupService) {
        return new BackupMarkerWriter(backupService);
    }
}
