package com.omniforge.core.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * models.yml 热重载监听（需求 4.1：配置变更无需重启）。
 *
 * <p>监听配置文件所在目录的创建/修改/删除事件，防抖 debounceMillis 毫秒后
 * 触发网关 reload；监听在虚拟线程中阻塞 take，不占用平台线程。</p>
 */
public class ModelConfigWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigWatcher.class);

    private final Path configFile;
    private final ModelGateway gateway;
    private final long debounceMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WatchService watchService;
    private volatile Thread watcherThread;

    public ModelConfigWatcher(Path configFile, ModelGateway gateway, long debounceMillis) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.debounceMillis = debounceMillis;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Path parent = configFile.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IllegalStateException("配置文件无父目录: " + configFile);
            }
            Files.createDirectories(parent);
            watchService = FileSystems.getDefault().newWatchService();
            parent.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("无法监听配置文件目录: " + configFile, e);
        }
        watcherThread = Thread.ofVirtual().name("omniforge-config-watcher", 0).start(this::watchLoop);
        log.info("已开启 models.yml 热重载监听：{}", configFile.toAbsolutePath());
    }

    private void watchLoop() {
        Path fileName = configFile.getFileName();
        WatchService ws = watchService;
        while (running.get() && ws != null) {
            try {
                WatchKey key = ws.take();
                boolean relevant = key.pollEvents().stream()
                        .anyMatch(event -> fileName.equals(((WatchEvent<?>) event).context()));
                key.reset();
                if (!relevant) {
                    continue;
                }
                Thread.sleep(debounceMillis); // 防抖：编辑器保存可能触发多次事件
                gateway.reload();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (Exception e) {
                // 热重载失败沿用旧配置（gateway.reload 内部已保证），此处仅记录
                log.error("配置热重载失败：{}", e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            log.debug("关闭配置监听失败：{}", e.getMessage());
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
