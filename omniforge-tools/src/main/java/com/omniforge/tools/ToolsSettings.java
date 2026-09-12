package com.omniforge.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具设置（2.5 配置 UI：用户可在配置中心切换，独立持久化于 tools.yml，
 * 经 {@link ToolsSettingsHolder} 热重载，无需重启）。
 *
 * @param shellEnabled          shell_executor 开关（默认 false，安全底线）
 * @param pythonEnabled         python_interpreter 开关（默认 true）
 * @param workspaceRoots        file_read_write 授权沙箱根目录列表（2026-09 支持多目录）；
 *                              空列表 = 沿用 ToolsProperties 默认工作区（相对路径可用）；
 *                              每项为授权目录（绝对路径，可含 D:\ 等），绝对路径必须落在任一项内，
 *                              用户自行承担安全责任。构造时统一 strip/滤空/去重，以可变 {@link ArrayList} 存储。
 * @param confirmationRequired  危险操作人工确认开关（U11，默认 true）：
 *                              true = Agent 调用写文件/Shell 等需确认工具时弹窗（HITL A1）；
 *                              false = 不经确认直接执行（用户自担风险，仅在 GUI 通道生效；
 *                              Headless/IM 本就无确认通道自动放行）。
 */
public record ToolsSettings(boolean shellEnabled, boolean pythonEnabled, List<String> workspaceRoots,
                            boolean confirmationRequired) {

    /** 紧凑构造：防御性归一化（null→空、strip、滤空白、去重），存储为可变 ArrayList */
    public ToolsSettings {
        List<String> normalized = new ArrayList<>();
        if (workspaceRoots != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String root : workspaceRoots) {
                if (root == null) {
                    continue;
                }
                String stripped = root.strip();
                if (!stripped.isBlank() && seen.add(stripped)) {
                    normalized.add(stripped);
                }
            }
        }
        workspaceRoots = normalized;
    }

    /** 兼容构造（不指定沙箱根目录 → 空列表 = 默认工作区；确认开关默认开启） */
    public ToolsSettings(boolean shellEnabled, boolean pythonEnabled) {
        this(shellEnabled, pythonEnabled, List.of(), true);
    }

    /** 兼容构造（3 参 → 确认开关默认开启） */
    public ToolsSettings(boolean shellEnabled, boolean pythonEnabled, List<String> workspaceRoots) {
        this(shellEnabled, pythonEnabled, workspaceRoots, true);
    }

    public static ToolsSettings from(ToolsProperties properties) {
        return new ToolsSettings(properties.isShellEnabled(), properties.isPythonEnabled(),
                List.of(), true);
    }

    /** 默认值（无配置时；危险操作人工确认安全默认开启） */
    public static ToolsSettings defaults() {
        return new ToolsSettings(false, true, List.of(), true);
    }
}
