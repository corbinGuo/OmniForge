package com.omniforge.tools.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 文件访问沙箱（需求 4.5：file_read_write 禁止访问系统目录）。
 *
 * <p>支持多个授权根目录（2026-09）：
 * <ul>
 *   <li>绝对路径：规范化后必须位于任一授权根目录内（元素级判定，{@code D:\a} 不包含 {@code D:\ab}）；</li>
 *   <li>相对路径：以调用方给定的 base（默认工作区）为根解析，且不可借 {@code ../} 越过 base——其它授权目录请用绝对路径；</li>
 *   <li>base 自身须位于任一授权根目录内（空目录列表=仅默认工作区的回退由 FileReadWriteTool 兜底）。</li>
 * </ul>
 *
 * <p>仅做词法校验（normalize），不解析符号链接——与既有行为一致；
 * 本特性本质是用户显式放开目录访问范围、自担风险，不做 realpath 检查。</p>
 */
public final class WorkspaceGuard {

    private final List<Path> roots;

    public WorkspaceGuard(Path root) {
        this(List.of(root));
    }

    /** @param roots 授权根目录（绝对或相对皆可，构造时统一绝对化+规范化；非空） */
    public WorkspaceGuard(List<Path> roots) {
        Objects.requireNonNull(roots, "roots");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("沙箱根目录列表不能为空");
        }
        this.roots = roots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    /** 全部授权根目录（已绝对化+规范化） */
    public List<Path> roots() {
        return roots;
    }

    /** 首个根目录（保留单根语义的便捷访问） */
    public Path root() {
        return roots.get(0);
    }

    /**
     * 单根相对路径便捷方法（历史兼容，勿改语义）：以首个根为工作区，仅接受相对路径，
     * 拒绝绝对路径；越界抛 {@link IllegalArgumentException}。
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path input = Path.of(relative);
        if (input.isAbsolute()) {
            throw new IllegalArgumentException("禁止绝对路径（仅允许工作区内相对路径）: " + relative);
        }
        Path resolved = root().resolve(input).normalize();
        if (!resolved.startsWith(root())) {
            throw new IllegalArgumentException("路径越界（禁止访问工作区外路径）: " + relative);
        }
        return resolved;
    }

    /** 绝对路径是否位于任一授权根目录内 */
    public boolean allows(Path absolute) {
        Path normalized = Objects.requireNonNull(absolute, "absolute").toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    /**
     * 多根分流解析：
     * <ul>
     *   <li>绝对输入 → 规范化后须落在任一授权根内，否则拒绝；</li>
     *   <li>相对输入 → 以 base（默认工作区）为根，base 须被任一授权根允许，且解析结果不可越过 base。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 越界 / base 缺失或未授权 / 输入非法时抛出
     */
    public Path resolveAccessible(String input, Path base) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path raw = Path.of(input);
        if (raw.isAbsolute()) {
            Path absolute = raw.normalize();
            if (!allows(absolute)) {
                throw new IllegalArgumentException(
                        "绝对路径越界（不在任一授权沙箱目录内）: " + input);
            }
            return absolute;
        }
        if (base == null) {
            throw new IllegalArgumentException("相对路径需要默认工作区作为根，但未提供");
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        if (!allows(normalizedBase)) {
            throw new IllegalArgumentException(
                    "默认工作区不在授权沙箱目录内，相对路径被拒绝（请用绝对路径，或把默认工作区加入授权目录）: " + base);
        }
        Path resolved = normalizedBase.resolve(raw).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("路径越界（相对路径不可越过默认工作区）: " + input);
        }
        return resolved;
    }
}
