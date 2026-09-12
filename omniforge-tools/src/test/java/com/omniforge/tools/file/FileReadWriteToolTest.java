package com.omniforge.tools.file;

import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.ToolsSettings;
import com.omniforge.tools.ToolsSettingsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadWriteToolTest {

    @TempDir
    Path tempDir;

    private FileReadWriteTool tool;

    @BeforeEach
    void setUp() {
        ToolsProperties properties = new ToolsProperties();
        properties.setWorkspaceRoot(tempDir);
        tool = new FileReadWriteTool(properties);
    }

    @Test
    void 写入后读取往返一致() {
        ToolResult write = tool.execute(request("write", "a/b.txt", "你好 OmniForge"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, write.status());

        ToolResult read = tool.execute(request("read", "a/b.txt", null));
        assertEquals(ToolResult.ToolStatus.SUCCESS, read.status());
        assertEquals("你好 OmniForge", read.output());
    }

    @Test
    void 追加写入() {
        tool.execute(request("write", "log.txt", "第一行\n"));
        ToolResult append = tool.execute(request("append", "log.txt", "第二行"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, append.status());
        assertEquals("第一行\n第二行", tool.execute(request("read", "log.txt", null)).output());
    }

    @Test
    void 路径越界被沙箱拒绝() {
        ToolResult result = tool.execute(request("read", "../../windows/system32/config", null));
        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
        assertTrue(result.error().contains("沙箱"), "越界应明确提示沙箱拒绝");
    }

    @Test
    void 目录列表() {
        tool.execute(request("write", "notes/1.txt", "x"));
        tool.execute(request("write", "notes/2.txt", "y"));

        ToolResult list = tool.execute(request("list", "notes", null));
        assertEquals(ToolResult.ToolStatus.SUCCESS, list.status());
        assertTrue(list.output().contains("1.txt"));
        assertTrue(list.output().contains("2.txt"));
    }

    @Test
    void 删除文件() throws Exception {
        tool.execute(request("write", "tmp.txt", "x"));
        ToolResult delete = tool.execute(request("delete", "tmp.txt", null));
        assertEquals(ToolResult.ToolStatus.SUCCESS, delete.status());
        assertTrue(Files.notExists(tempDir.resolve("tmp.txt")));
    }

    @Test
    void 不存在的文件读取报错() {
        ToolResult read = tool.execute(request("read", "no-such.txt", null));
        assertEquals(ToolResult.ToolStatus.FAILED, read.status());
    }

    @Test
    void 不支持的操作为失败结果() {
        ToolResult result = tool.execute(request("chmod", "a.txt", null));
        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
    }

    // ---------- 多授权沙箱目录（2026-09） ----------

    @Test
    void 多授权目录下绝对路径读写两不同根() {
        Path rootA = tempDir.resolve("data");
        Path rootB = tempDir.resolve("docs");
        FileReadWriteTool multi = new FileReadWriteTool(new ToolsProperties(),
                new ToolsSettingsHolder(new ToolsSettings(false, true,
                        List.of(rootA.toString(), rootB.toString()))));

        ToolResult w1 = multi.execute(request("write", rootA.resolve("a.txt").toString(), "A"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, w1.status());
        ToolResult w2 = multi.execute(request("write", rootB.resolve("b.txt").toString(), "B"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, w2.status());
        assertEquals("A", multi.execute(request("read", rootA.resolve("a.txt").toString(), null)).output());
        assertEquals("B", multi.execute(request("read", rootB.resolve("b.txt").toString(), null)).output());
    }

    @Test
    void 授权目录外的绝对路径被拒绝() {
        Path rootA = tempDir.resolve("data");
        FileReadWriteTool multi = new FileReadWriteTool(new ToolsProperties(),
                new ToolsSettingsHolder(new ToolsSettings(false, true, List.of(rootA.toString()))));

        ToolResult result = multi.execute(request("read", tempDir.resolve("outside.txt").toString(), null));
        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
        assertTrue(result.error().contains("沙箱"), "越界应明确提示沙箱拒绝");
    }

    @Test
    void 默认工作区已授权时相对路径可用() {
        Path rootA = tempDir.resolve("data");
        ToolsProperties properties = new ToolsProperties();
        properties.setWorkspaceRoot(rootA);
        FileReadWriteTool toolA = new FileReadWriteTool(properties,
                new ToolsSettingsHolder(new ToolsSettings(false, true, List.of(rootA.toString()))));

        ToolResult write = toolA.execute(request("write", "sub/x.txt", "rel"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, write.status());
        assertEquals("rel", toolA.execute(request("read", "sub/x.txt", null)).output());
    }

    @Test
    void 默认工作区未授权时相对路径被拒绝但授权内绝对路径可用() {
        Path rootA = tempDir.resolve("data"); // 默认工作区（未授权）
        Path rootB = tempDir.resolve("docs"); // 唯一授权目录
        ToolsProperties properties = new ToolsProperties();
        properties.setWorkspaceRoot(rootA);
        FileReadWriteTool toolA = new FileReadWriteTool(properties,
                new ToolsSettingsHolder(new ToolsSettings(false, true, List.of(rootB.toString()))));

        ToolResult rel = toolA.execute(request("read", "x.txt", null));
        assertEquals(ToolResult.ToolStatus.FAILED, rel.status());
        assertTrue(rel.error().contains("沙箱"));

        ToolResult abs = toolA.execute(request("write", rootB.resolve("y.txt").toString(), "ok"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, abs.status());
    }

    @Test
    void 空授权列表回退默认工作区且目录内绝对路径可写() {
        Path ws = tempDir.resolve("ws");
        ToolsProperties properties = new ToolsProperties();
        properties.setWorkspaceRoot(ws);
        FileReadWriteTool toolWs = new FileReadWriteTool(properties,
                new ToolsSettingsHolder(new ToolsSettings(false, true, List.of())));

        ToolResult rel = toolWs.execute(request("write", "r.txt", "rel"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, rel.status());
        // 行为扩展：空列表 = 仅默认工作区授权，默认工作区内的绝对路径现可写（相对旧实现）
        ToolResult abs = toolWs.execute(request("write", ws.resolve("abs.txt").toString(), "abs"));
        assertEquals(ToolResult.ToolStatus.SUCCESS, abs.status());
        assertTrue(Files.exists(ws.resolve("abs.txt")));
    }

    private static ToolRequest request(String operation, String path, String content) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("operation", operation);
        params.put("path", path);
        if (content != null) {
            params.put("content", content);
        }
        return ToolRequest.of(params);
    }

    // ---------- HITL 操作级确认（TOOL_CONFIRMATION Q1-B） ----------

    @Test
    void 写追加删除操作需人工确认() {
        assertTrue(tool.requiresConfirmation(Map.of("operation", "write", "path", "x.txt")));
        assertTrue(tool.requiresConfirmation(Map.of("operation", "append", "path", "x.txt")));
        assertTrue(tool.requiresConfirmation(Map.of("operation", "delete", "path", "x.txt")));
    }

    @Test
    void 读列目录操作无需确认() {
        org.junit.jupiter.api.Assertions.assertFalse(
                tool.requiresConfirmation(Map.of("operation", "read", "path", "x.txt")));
        org.junit.jupiter.api.Assertions.assertFalse(
                tool.requiresConfirmation(Map.of("operation", "list", "path", "dir")));
    }

    @Test
    void 缺operation参数时无需确认() {
        org.junit.jupiter.api.Assertions.assertFalse(tool.requiresConfirmation(Map.of()));
        org.junit.jupiter.api.Assertions.assertFalse(tool.requiresConfirmation(null));
    }
}
