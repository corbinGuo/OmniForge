package com.omniforge.core.persistence;

import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import com.omniforge.core.persistence.service.ImMessageDedupService;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;
import java.nio.file.Files;

/**
 * 持久化自动装配（单机版 SQLite，WAL 模式）。
 *
 * <p>职责：
 * <ul>
 *   <li>提供 SQLite DataSource（Hikari 连接池，连接初始化 PRAGMA journal_mode=WAL / foreign_keys=ON）；</li>
 *   <li>通过 {@link HibernatePropertiesCustomizer} 设定默认方言（hibernate-community SQLiteDialect）与
 *       hbm2ddl.auto=update（表结构自动生成，Phase 1 #6）；</li>
 *   <li>声明实体扫描（com.omniforge.core.persistence.entity）与仓库扫描（...repository）。</li>
 * </ul>
 *
 * <p>注：SQLite 的 :memory: 库是"每连接独立库"，测试模式强制单连接池避免分库现象。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(PersistenceProperties.class)
@EntityScan(basePackages = "com.omniforge.core.persistence.entity")
@EnableJpaRepositories(basePackages = "com.omniforge.core.persistence.repository")
public class PersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource(PersistenceProperties properties) throws java.io.IOException {
        HikariDataSource dataSource = new HikariDataSource();
        if (properties.isInMemory()) {
            // 内存库：共享缓存 + 单连接（SQLite :memory: 每连接独立库）
            dataSource.setJdbcUrl("jdbc:sqlite:file::memory:?cache=shared");
            dataSource.setMaximumPoolSize(1);
        } else {
            java.nio.file.Path file = java.nio.file.Path.of(properties.getDatabaseFile()).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            dataSource.setJdbcUrl("jdbc:sqlite:" + file);
        }
        StringBuilder pragmas = new StringBuilder();
        if (properties.isWalEnabled()) {
            pragmas.append("PRAGMA journal_mode=WAL;");
        }
        if (properties.isForeignKeysEnabled()) {
            pragmas.append("PRAGMA foreign_keys=ON;");
        }
        dataSource.setConnectionInitSql(pragmas.toString());
        dataSource.setPoolName("omniforge-sqlite");
        return dataSource;
    }

    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return hibernateProperties -> {
            // SQLite 社区方言 + 表结构自动生成（Phase 1 #6：ddl-auto=update）
            hibernateProperties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
            hibernateProperties.put("hibernate.hbm2ddl.auto", "update");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ImMessageDedupService imMessageDedupService(ImMessageDedupRepository repository) {
        return new ImMessageDedupService(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.omniforge.core.persistence.service.LicenseService licenseService(
            com.omniforge.core.persistence.repository.LicenseRepository licenseRepository) {
        return new com.omniforge.core.persistence.service.LicenseService(licenseRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.omniforge.core.persistence.service.DebateRecordService debateRecordService(
            com.omniforge.core.persistence.repository.DebateRecordRepository recordRepository,
            com.omniforge.core.persistence.repository.SessionRepository sessionRepository,
            com.omniforge.core.persistence.repository.MessageRepository messageRepository,
            com.omniforge.core.persistence.repository.WorkspaceRepository workspaceRepository) {
        return new com.omniforge.core.persistence.service.DebateRecordService(
                recordRepository, sessionRepository, messageRepository, workspaceRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.omniforge.core.persistence.service.ChatSessionService chatSessionService(
            com.omniforge.core.persistence.repository.SessionRepository sessionRepository,
            com.omniforge.core.persistence.repository.MessageRepository messageRepository,
            com.omniforge.core.persistence.repository.WorkspaceRepository workspaceRepository) {
        return new com.omniforge.core.persistence.service.ChatSessionService(
                sessionRepository, messageRepository, workspaceRepository);
    }
}
