package com.omniforge.gateway;

import java.util.List;
import java.util.Objects;

/**
 * 白名单权限校验（需求 4.6：支持白名单配置）。
 *
 * <p>规则：
 * <ul>
 *   <li>enabled=false → 放行全部（默认，与 shell 工具同款安全哲学：显式开启才生效）；</li>
 *   <li>enabled=true 且未配置任何发送者 → 拒绝全部（避免误开导致全放行）；</li>
 *   <li>支持精确匹配与前缀通配（"admin_*"），"*" 放行全部。</li>
 * </ul>
 */
public class ImWhitelist {

    private final boolean enabled;
    private final List<String> patterns;

    public ImWhitelist(boolean enabled, List<String> patterns) {
        this.enabled = enabled;
        this.patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /** 是否允许该发送者 */
    public boolean allows(String sender) {
        if (!enabled) {
            return true;
        }
        if (patterns.isEmpty()) {
            return false; // 开启但未配置 → 拒绝（安全底线）
        }
        return patterns.stream().anyMatch(pattern -> match(pattern, sender));
    }

    public boolean isEnabled() {
        return enabled;
    }

    static boolean match(String pattern, String sender) {
        Objects.requireNonNull(pattern, "pattern");
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return sender != null && sender.startsWith(prefix);
        }
        return pattern.equals(sender);
    }
}
