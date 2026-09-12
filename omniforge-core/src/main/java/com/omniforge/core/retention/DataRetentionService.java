package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 数据保留调度服务（DATA_RETENTION Q1-A/Q3-A）：
 * 启动时立即清理一次 + 每日后台扫描；enabled=false 全部跳过。
 * 各 target 隔离：单个失败仅 WARN，不中断其余 target。
 */
public class DataRetentionService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    /** 每日扫描间隔（设计 §3.1：每日一次） */
    private static final long DAILY_HOURS = 24;

    private final RetentionSettingsHolder settingsHolder;
    private final List<RetentionTarget> targets;
    private volatile ScheduledExecutorService sweeper;
    private volatile boolean running;

    public DataRetentionService(RetentionSettingsHolder settingsHolder, List<RetentionTarget> targets) {
        this.settingsHolder = settingsHolder;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    // ---------- SmartLifecycle（启动一次 + 每日扫描） ----------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        // 启动即扫一次（Q3-A：启动时一次；虚拟线程不阻塞启动）
        Thread.ofVirtual().name("omniforge-retention-startup", 0).start(this::runOnce);
        sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "omniforge-retention-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::runOnce, DAILY_HOURS, DAILY_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService current = sweeper;
        sweeper = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------- 清理执行 ----------

    /** 立即执行一次保留清理（配置中心「立即清理旧数据」按钮调用，Q3-A）；返回各 target 删除数摘要 */
    public String runOnce() {
        RetentionSettings settings = settingsHolder.current();
        if (!settings.isEnabled()) {
            log.info("数据保留策略未启用，跳过清理");
            return "";
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime chatCutoff = now.minus(settings.chatDays(), ChronoUnit.DAYS);
        LocalDateTime logCutoff = now.minus(settings.logDays(), ChronoUnit.DAYS);
        LocalDateTime auditCutoff = now.minus(settings.auditDays(), ChronoUnit.DAYS);
        StringBuilder summary = new StringBuilder();
        for (RetentionTarget target : targets) {
            LocalDateTime cutoff = switch (target.name()) {
                case "session" -> chatCutoff;
                case "audit" -> auditCutoff;
                default -> logCutoff; // im / tool-log 共用 logDays
            };
            try {
                int removed = target.cleanup(cutoff);
                log.info("数据保留清理：{} 删除 {} 条", target.name(), removed);
                if (summary.length() > 0) {
                    summary.append("，");
                }
                summary.append(target.name()).append(" ").append(removed);
            } catch (RuntimeException e) {
                log.warn("数据保留清理失败（{}）：{}", target.name(), e.getMessage());
            }
        }
        return summary.toString();
    }
}
