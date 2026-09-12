package com.omniforge.tools.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Python 代码黑名单校验（需求 4.5：黑名单拦截危险命令）。
 * 返回 null 表示通过；否则返回命中的黑名单规则文本。
 */
public final class PythonGuard {

    private PythonGuard() {
    }

    /**
     * 校验代码；命中时返回命中的模式文本。
     *
     * @param code     待执行代码
     * @param patterns 黑名单正则（由 ToolsProperties 配置）
     */
    public static String findViolation(String code, List<Pattern> patterns) {
        if (code == null) {
            return null;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(code).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }
}
