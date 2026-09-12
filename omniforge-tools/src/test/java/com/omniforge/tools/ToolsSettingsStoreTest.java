package com.omniforge.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final ToolsSettingsStore store = new ToolsSettingsStore();

    private Path file() {
        return tempDir.resolve("tools.yml");
    }

    @Test
    void 缺失文件返回默认() {
        assertEquals(ToolsSettings.defaults(), store.load(file()));
    }

    @Test
    void 损坏文件返回默认() throws Exception {
        Files.writeString(file(), "shellEnabled: [unclosed");
        assertEquals(ToolsSettings.defaults(), store.load(file()));
    }

    @Test
    void 多目录列表往返去空白去重() throws Exception {
        ToolsSettings settings = new ToolsSettings(true, true,
                List.of("D:\\data", " E:\\docs ", "D:\\data"));
        store.save(file(), settings);

        ToolsSettings loaded = store.load(file());
        assertTrue(loaded.shellEnabled());
        assertTrue(loaded.pythonEnabled());
        assertEquals(List.of("D:\\data", "E:\\docs"), loaded.workspaceRoots(), "应去空白并去重");
    }

    @Test
    void 仅开关无root字段() throws Exception {
        Files.writeString(file(), "pythonEnabled: false\n");
        ToolsSettings loaded = store.load(file());

        assertFalse(loaded.pythonEnabled(), "显式 false 应保留");
        assertFalse(loaded.shellEnabled(), "shell 缺省保持默认 false");
        assertTrue(loaded.workspaceRoots().isEmpty(), "无 root 字段 → 空列表 = 默认工作区");
    }

    @Test
    void 旧标量workspaceRoot迁移且开关保留() throws Exception {
        // 392da58 发布的单值格式
        Files.writeString(file(), "shellEnabled: true\npythonEnabled: false\nworkspaceRoot: 'D:\\legacy'\n");
        ToolsSettings loaded = store.load(file());

        assertTrue(loaded.shellEnabled());
        assertFalse(loaded.pythonEnabled());
        assertEquals(List.of("D:\\legacy"), loaded.workspaceRoots(), "旧标量应迁移为单元素列表");
    }
}
