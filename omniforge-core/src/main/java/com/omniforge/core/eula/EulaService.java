package com.omniforge.core.eula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * EULA 同意状态管理（Phase 4，需求 9.2 安全底线：首次启动必须显示 EULA 同意书）。
 * 状态持久化于 <配置目录>/eula.accepted（含接受时间）。
 */
public class EulaService {

    private static final String ACCEPT_FILE = "eula.accepted";

    private final Path configDir;

    public EulaService(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    /** 是否已同意（文件存在即视为已同意） */
    public boolean isAccepted() {
        return Files.exists(acceptFile());
    }

    /** 记录同意（含时间戳） */
    public void accept() {
        try {
            Files.createDirectories(configDir);
            Files.writeString(acceptFile(), "accepted-at: " + LocalDateTime.now() + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("EULA 同意状态写入失败", e);
        }
    }

    private Path acceptFile() {
        return configDir.resolve(ACCEPT_FILE);
    }
}
