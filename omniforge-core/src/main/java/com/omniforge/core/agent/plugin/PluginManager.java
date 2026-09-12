package com.omniforge.core.agent.plugin;

import com.omniforge.common.spi.OmniForgePlugin;
import com.omniforge.common.spi.Tool;
import com.omniforge.core.agent.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件管理器（C-tier 批次 3：plugins/*.jar 热加载，需求 4.5）。
 *
 * <p>启动时扫描插件目录一次，之后 WatchService 监听目录变化：
 * jar 新增/内容变化 → 加载（已加载先卸载再重载）；jar 删除 → 卸载对应工具。</p>
 *
 * <p><b>staging 副本（Windows 文件锁）</b>：URLClassLoader 持有 jar 打开句柄，
 * Windows 下用户无法删除/覆盖正在使用的原文件。本实现把 jar 复制到
 * {@code plugins/.loaded/} 从副本加载，原文件可自由增删改。</p>
 *
 * <p><b>类加载</b>：双亲委托（parent-first），插件复用应用类路径上的
 * omniforge-common（避免类版本分裂）；卸载时 close 类加载器并丢弃引用，
 * 类元数据待 GC 回收（Java 插件加载通用约束）。</p>
 */
public class PluginManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** staging 子目录名（watch 事件过滤用） */
    static final String STAGING_DIR = ".loaded";

    private final PluginProperties properties;
    private final ToolRegistry toolRegistry;
    /** 原 jar 路径 → 加载记录（含工具名清单，卸载时反向清理） */
    private final Map<Path, PluginRecord> loaded = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile WatchService watchService;
    private volatile Thread watchThread;

    public PluginManager(PluginProperties properties, ToolRegistry toolRegistry) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    /** 单个插件的加载记录 */
    private record PluginRecord(URLClassLoader classLoader, Path stagedJar,
                                long size, long modified, List<String> toolNames,
                                List<String> pluginNames) {
    }

    /** 已加载插件的运行时信息（P1-3 插件市场 UI 展示） */
    public record LoadedPlugin(String jarFileName, List<String> pluginNames, List<String> toolNames) {
    }

    /** 已加载插件列表（按 jar 文件名的原始顺序）；仅含加载成功的 jar */
    public List<LoadedPlugin> listLoaded() {
        synchronized (loaded) {
            return loaded.entrySet().stream()
                    .map(entry -> new LoadedPlugin(entry.getKey().getFileName().toString(),
                            List.copyOf(entry.getValue().pluginNames()),
                            List.copyOf(entry.getValue().toolNames())))
                    .toList();
        }
    }

    @Override
    public void start() {
        if (!properties.isEnabled() || running.compareAndSet(false, true) == false) {
            return;
        }
        try {
            Files.createDirectories(properties.getPluginsDir());
            Files.createDirectories(stagingDir());
            scanDirectory();
            startWatcher();
            log.info("插件管理已启动：扫描 {}，已加载 {} 个插件 jar",
                    properties.getPluginsDir().toAbsolutePath(), loaded.size());
        } catch (IOException e) {
            running.set(false);
            log.warn("插件目录初始化失败（插件热加载关闭）：{}", e.getMessage());
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
            log.debug("关闭 WatchService 失败：{}", e.getMessage());
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (loaded) {
            for (Path jar : new ArrayList<>(loaded.keySet())) {
                unload(jar);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 已加载插件名列表（诊断/状态展示用） */
    public Collection<String> loadedPluginNames() {
        synchronized (loaded) {
            return loaded.values().stream()
                    .flatMap(record -> record.pluginNames().stream())
                    .toList();
        }
    }

    /** 包内可见：测试直接触发扫描 */
    void scanDirectory() throws IOException {
        try (var stream = Files.list(properties.getPluginsDir())) {
            for (Path jar : stream.filter(PluginManager::isPluginJar).sorted().toList()) {
                load(jar);
            }
        }
    }

    /** 目录变化事件处理：存在 → 加载/重载；不存在 → 卸载（防抖后由 watcher 调用） */
    void processPath(Path jar) {
        if (Files.isRegularFile(jar) && isPluginJar(jar)) {
            load(jar);
        } else {
            unload(jar);
        }
    }

    private Path stagingDir() {
        return properties.getPluginsDir().resolve(STAGING_DIR);
    }

    private static boolean isPluginJar(Path path) {
        return path != null && Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(".jar");
    }

    /** 加载插件 jar（已加载同路径：内容未变跳过，内容变化先卸载重载） */
    private void load(Path jar) {
        synchronized (loaded) {
            PluginRecord existing = loaded.get(jar);
            try {
                if (existing != null && existing.size() == Files.size(jar)
                        && existing.modified() == Files.getLastModifiedTime(jar).toMillis()) {
                    return; // 内容未变化（防抖窗口内的重复事件）
                }
                if (existing != null) {
                    unload(jar);
                }
                Path staged = stagingDir().resolve(jar.getFileName().toString());
                Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);

                List<String> toolNames = new ArrayList<>();
                List<String> pluginNames = new ArrayList<>();
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{staged.toUri().toURL()},
                        OmniForgePlugin.class.getClassLoader());
                try {
                    boolean anyPlugin = false;
                    for (OmniForgePlugin plugin : ServiceLoader.load(OmniForgePlugin.class, loader)) {
                        anyPlugin = true;
                        registerPlugin(plugin, toolNames, pluginNames, jar);
                    }
                    if (!anyPlugin) {
                        loader.close();
                        Files.deleteIfExists(staged);
                        log.warn("插件 jar 无 OmniForgePlugin 实现，已跳过：{}", jar.getFileName());
                        return;
                    }
                    PluginRecord record = new PluginRecord(loader, staged,
                            Files.size(jar), Files.getLastModifiedTime(jar).toMillis(),
                            toolNames, pluginNames);
                    loaded.put(jar, record);
                    log.info("插件已加载：{}（{} 个插件，注册工具 {} 个）",
                            jar.getFileName(), pluginNames.size(), toolNames.size());
                } catch (Throwable e) {
                    // 插件隔离底线：坏插件 jar 不得拖垮宿主应用。
                    // ServiceConfigurationError/NoClassDefFoundError 等 Error 也在此兜住，
                    // 并回滚本 jar 已注册的工具
                    for (String toolName : toolNames) {
                        toolRegistry.unregister(toolName);
                    }
                    try {
                        loader.close();
                    } catch (IOException closeError) {
                        log.debug("关闭插件类加载器失败：{}", closeError.getMessage());
                    }
                    try {
                        Files.deleteIfExists(staged);
                    } catch (IOException deleteError) {
                        log.debug("删除插件 staging 副本失败：{}", deleteError.getMessage());
                    }
                    log.warn("插件 jar 加载失败（已跳过并回滚）：{} —— {}", jar.getFileName(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("插件 jar 加载失败（已跳过）：{} —— {}", jar.getFileName(), e.getMessage());
            }
        }
    }

    private void registerPlugin(OmniForgePlugin plugin, List<String> toolNames,
                                List<String> pluginNames, Path jar) {
        String name = plugin.getName();
        pluginNames.add(name == null || name.isBlank() ? jar.getFileName().toString() : name);
        for (Tool tool : plugin.getTools()) {
            if (tool == null || tool.spec() == null) {
                log.warn("插件 {} 提供了空工具条目，已忽略", name);
                continue;
            }
            String toolName = tool.spec().name();
            if (toolRegistry.register(tool)) {
                toolNames.add(toolName);
            } else {
                log.warn("插件 {} 的工具 {} 与现有工具同名，未注册（先注册者胜）", name, toolName);
            }
        }
    }

    /** 卸载插件 jar：注销工具 → 关闭类加载器 → 删除 staging 副本 */
    private void unload(Path jar) {
        PluginRecord record;
        synchronized (loaded) {
            record = loaded.remove(jar);
        }
        if (record == null) {
            return;
        }
        for (String toolName : record.toolNames()) {
            toolRegistry.unregister(toolName);
        }
        try {
            record.classLoader().close();
        } catch (IOException e) {
            log.debug("关闭插件类加载器失败：{}", e.getMessage());
        }
        try {
            Files.deleteIfExists(record.stagedJar());
        } catch (IOException e) {
            log.debug("删除插件 staging 副本失败：{}", e.getMessage());
        }
        log.info("插件已卸载：{}（注销工具 {} 个）", jar.getFileName(), record.toolNames().size());
    }

    /** WatchService 监听插件目录（守护线程），事件防抖后交给虚拟线程处理 */
    private void startWatcher() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        properties.getPluginsDir().register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        long debounce = Math.max(0, properties.getWatchDebounceMillis());
        watchThread = Thread.ofPlatform().daemon().name("plugin-watcher").start(() -> {
            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.nio.file.ClosedWatchServiceException e) {
                    return;
                }
                if (key == null) {
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!(event.context() instanceof Path relative)) {
                        continue;
                    }
                    Path jar = properties.getPluginsDir().resolve(relative);
                    // staging 目录内的事件来自本管理器自身的复制操作，忽略
                    if (relative.startsWith(STAGING_DIR)) {
                        continue;
                    }
                    if (!jar.getFileName().toString().endsWith(".jar")) {
                        continue;
                    }
                    executor.submit(() -> {
                        try {
                            Thread.sleep(debounce);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        processPath(jar);
                    });
                }
                key.reset();
            }
        });
    }
}
