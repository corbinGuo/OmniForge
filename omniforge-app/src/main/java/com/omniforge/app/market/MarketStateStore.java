package com.omniforge.app.market;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 市场安装状态持久化（state.json，配置目录下 market/state.json）。
 * 文件缺失/损坏返回空状态（不阻断市场）；保存自动创建父目录。
 */
public class MarketStateStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public MarketState load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return MarketState.empty();
        }
        try {
            return JSON.readValue(Files.readAllBytes(file), MarketState.class);
        } catch (IOException | IllegalArgumentException e) {
            return MarketState.empty();
        }
    }

    public void save(Path file, MarketState state) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(state, "state");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        JSON.writeValue(file.toFile(), state);
    }
}
