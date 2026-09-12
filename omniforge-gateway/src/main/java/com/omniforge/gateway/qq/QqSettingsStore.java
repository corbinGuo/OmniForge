package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * QQ 官方机器人配置持久化（qq-im.yml，配置目录下）+ 运行时热生效：
 * 保存后立即更新内存值，Adapter/Receiver/Sender 均经 {@link #current()} 读取。
 * 文件缺失或损坏时返回默认值（全关闭）。
 */
public class QqSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Path file;
    private volatile QqSettings current;

    public QqSettingsStore() {
        this(defaultConfigDir());
    }

    /** @param configDir 配置目录（测试可注入临时目录） */
    public QqSettingsStore(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        this.file = configDir.resolve("qq-im.yml");
        this.current = load(file);
    }

    private QqSettings load(Path path) {
        if (!Files.exists(path)) {
            return QqSettings.defaults();
        }
        try {
            QqSettings settings = yamlMapper.readValue(Files.readAllBytes(path), QqSettings.class);
            return settings == null ? QqSettings.defaults() : settings.normalize();
        } catch (IOException e) {
            return QqSettings.defaults();
        }
    }

    /** 当前生效配置（运行时热切换的读取点） */
    public QqSettings current() {
        return current;
    }

    /** 配置文件路径（UI 提示用） */
    public Path file() {
        return file;
    }

    /** 保存配置并立即热生效 */
    public synchronized void save(QqSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        QqSettings normalized = settings.normalize();
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), normalized);
        this.current = normalized;
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
    private static Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(userHome);
            return base.resolve("OmniForge");
        }
        return Paths.get(userHome, ".omniforge");
    }
}
