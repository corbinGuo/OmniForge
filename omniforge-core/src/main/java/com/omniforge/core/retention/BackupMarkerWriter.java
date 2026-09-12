package com.omniforge.core.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Schema 标记回写器（DATA_RETENTION A4）：
 * 上下文<b>成功启动</b>（ContextRefreshedEvent）后才把当前 {@link AppSchema#schemaGeneration()}
 * 写入 {@code .schema-version}——迁移中途崩溃不污染标记，下次启动仍会再备份。
 */
public class BackupMarkerWriter implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BackupMarkerWriter.class);

    private final BackupService backupService;

    public BackupMarkerWriter(BackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            BackupService.writeMarker(backupService.markerFile(), AppSchema.schemaGeneration());
        } catch (RuntimeException e) {
            log.warn("schema 标记回写失败（已跳过）：{}", e.getMessage());
        }
    }
}
