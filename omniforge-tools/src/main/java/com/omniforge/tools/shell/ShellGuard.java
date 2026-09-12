package com.omniforge.tools.shell;

import java.util.List;

/**
 * Shell 命令黑名单（需求 4.5：shell_executor 默认禁用，开启后仍需拦截危险命令）。
 *
 * <p>策略：前缀匹配（规范化空白与大小写后比较），命中即拒绝执行。</p>
 */
public final class ShellGuard {

    /** 默认黑名单前缀（可经 ToolsProperties.shell-blacklist 扩展） */
    public static final List<String> DEFAULT_BLACKLIST = List.of(
            "rm -rf /", "rm -rf /*", "del /f /s /q c:\\", "format ", "shutdown ", "taskkill ",
            "mkfs", "dd if=", ":(){ :|:& };:", "powershell -enc", "curl | sh", "wget | sh");

    private ShellGuard() {
    }

    /**
     * 校验命令；命中黑名单时返回命中的规则文本，否则返回 null。
     */
    public static String findViolation(String command, List<String> blacklist) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.toLowerCase().replaceAll("\\s+", " ").trim();
        for (String banned : blacklist) {
            String bannedNormalized = banned.toLowerCase().replaceAll("\\s+", " ").trim();
            if (normalized.startsWith(bannedNormalized) || normalized.contains(" " + bannedNormalized + " ")) {
                return banned;
            }
        }
        return null;
    }
}
