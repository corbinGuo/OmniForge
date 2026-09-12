package com.omniforge.app.retention;

import com.omniforge.core.persistence.PersistenceProperties;
import com.omniforge.core.retention.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

/**
 * 升级自动备份初始化器（DATA_RETENTION A4）：
 * 在任何 EMF/Hikari 装配之前（context refresh 前）经 {@link Binder} 绑定
 * PersistenceProperties + KnowledgeProperties 取库路径，比对 schema 标记执行备份。
 *
 * <p>三模式（GUI / headless / mcp-server）统一经
 * {@code SpringApplicationBuilder.initializers(...)} 注入（OmniForgeLauncher）。</p>
 */
public class BackupInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(BackupInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        try {
            Binder binder = Binder.get(context.getEnvironment());
            BindResult<PersistenceProperties> persistence = binder.bind(
                    "omniforge.persistence", PersistenceProperties.class);
            if (!persistence.isBound()) {
                return; // 未装配（理论不发生在 GUI/headless；不强行备份）
            }
            PersistenceProperties properties = persistence.get();
            if (properties.isInMemory()) {
                return; // :memory: 测试库无需备份
            }
            Path dbFile = Path.of(properties.getDatabaseFile());
            Path vectorsFile = binder.bind("omniforge.knowledge.vector-db-file", String.class)
                    .map(Path::of)
                    .orElse(dbFile.toAbsolutePath().getParent().resolve("vectors.db"));
            Path backupDir = dbFile.toAbsolutePath().getParent().resolve("backups");
            int copied = BackupService.backup(dbFile, vectorsFile, backupDir, false);
            if (copied > 0) {
                log.info("升级自动备份完成：{} 个文件 → {}", copied, backupDir);
            }
        } catch (RuntimeException e) {
            // 备份失败绝不让应用无法启动（保留策略是增强不是阻塞）
            log.warn("升级自动备份跳过：{}", e.getMessage());
        }
    }
}
