package com.omniforge.tools.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceGuardTest {

    @TempDir
    Path tempDir;

    private WorkspaceGuard guard;

    @BeforeEach
    void setUp() {
        guard = new WorkspaceGuard(tempDir);
    }

    @Test
    void 合法相对路径解析为工作区内路径() {
        Path resolved = guard.resolve("docs/notes.txt");
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("docs/notes.txt"), resolved);
    }

    @Test
    void 规范化后仍在工作区内() {
        Path resolved = guard.resolve("a/../b.txt");
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("b.txt"), resolved);
    }

    @Test
    void 上级目录穿越被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("a/../../secret"));
    }

    @Test
    void 绝对路径被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> guard.resolve(tempDir.resolve("x").toString()));
        assertThrows(IllegalArgumentException.class, () -> guard.resolve("C:\\Windows\\System32"));
    }

    @Test
    void 空路径被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> guard.resolve(" "));
    }

    // ---------- 多授权根目录（2026-09） ----------

    private WorkspaceGuard multiGuard(Path rootA, Path rootB) {
        return new WorkspaceGuard(List.of(rootA, rootB));
    }

    @Test
    void 授权根列表不能为空() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceGuard(List.of()));
    }

    @Test
    void 多根下任一目录内绝对路径放行() {
        Path rootA = tempDir.resolve("data");
        Path rootB = tempDir.resolve("docs");
        WorkspaceGuard multi = multiGuard(rootA, rootB);

        assertEquals(rootA.toAbsolutePath().normalize().resolve("a.pdf"),
                multi.resolveAccessible(rootA.resolve("a.pdf").toString(), tempDir));
        assertEquals(rootB.toAbsolutePath().normalize().resolve("b.md"),
                multi.resolveAccessible(rootB.resolve("b.md").toString(), tempDir));
    }

    @Test
    void 授权目录外的绝对路径被拒绝() {
        Path rootA = tempDir.resolve("data");
        WorkspaceGuard multi = multiGuard(rootA, tempDir.resolve("docs"));

        assertThrows(IllegalArgumentException.class,
                () -> multi.resolveAccessible(tempDir.resolve("outside.txt").toString(), tempDir));
    }

    @Test
    void 根目录按元素级边界判定不误含() {
        Path rootA = tempDir.resolve("data");
        WorkspaceGuard multi = multiGuard(rootA, tempDir.resolve("docs"));

        // D:\a 不应包含 D:\ab（data 不包含 data2）
        assertFalse(multi.allows(tempDir.resolve("data2").resolve("x.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> multi.resolveAccessible(tempDir.resolve("data2").resolve("x.txt").toString(), tempDir));
    }

    @Test
    void 相对路径以授权的base解析() {
        Path rootA = tempDir.resolve("data");
        Path rootB = tempDir.resolve("docs");
        WorkspaceGuard multi = multiGuard(rootA, rootB);

        // base = rootA（本身是授权根）→ 相对路径在其下解析
        Path resolved = multi.resolveAccessible("sub/f.txt", rootA);
        assertEquals(rootA.toAbsolutePath().normalize().resolve("sub/f.txt"), resolved);
    }

    @Test
    void base未授权时相对路径被拒绝() {
        Path rootA = tempDir.resolve("data");
        WorkspaceGuard multi = multiGuard(rootA, tempDir.resolve("docs"));

        // base = tempDir 不在授权根列表内 → 相对路径拒绝
        assertThrows(IllegalArgumentException.class, () -> multi.resolveAccessible("f.txt", tempDir));
    }

    @Test
    void 相对路径不可上浮到其他授权根() {
        Path rootA = tempDir.resolve("data");
        Path rootB = tempDir.resolve("docs");
        WorkspaceGuard multi = multiGuard(rootA, rootB);

        // base=rootA 下相对 ../docs/x 被拒（相对路径只能停留在 base 内）
        assertThrows(IllegalArgumentException.class,
                () -> multi.resolveAccessible("../docs/x.md", rootA));
    }

    @Test
    void base为空时相对路径被拒绝() {
        Path rootA = tempDir.resolve("data");
        WorkspaceGuard multi = multiGuard(rootA, tempDir.resolve("docs"));
        assertThrows(IllegalArgumentException.class, () -> multi.resolveAccessible("f.txt", null));
    }
}
