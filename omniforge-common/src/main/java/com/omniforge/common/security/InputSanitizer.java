package com.omniforge.common.security;

/**
 * 输入清洗（Phase 4，需求 9.2 安全底线：输入清洗防注入）。
 *
 * <p>策略：去除控制字符（保留 \n \t，防止终端注入与隐藏字符攻击）、
 * 去除零宽字符（BOM/ZWSP 等）、长度截断（默认 10000 字符）。
 * 接入口：IM 消息路由（对外输入的统一入口）。</p>
 */
public final class InputSanitizer {

    public static final int DEFAULT_MAX_LENGTH = 10_000;

    private InputSanitizer() {
    }

    public static String sanitize(String input) {
        return sanitize(input, DEFAULT_MAX_LENGTH);
    }

    /**
     * 清洗输入；返回安全文本（永不返回 null，空输入返回空串）。
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length() && sb.length() < maxLength; i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c); // 保留合法换行与制表符
            } else if (Character.isISOControl(c)) {
                sb.append(' '); // 其余控制字符替换为空格
            } else if (isZeroWidth(c)) {
                // 零宽字符直接丢弃
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static boolean isZeroWidth(char c) {
        return c == '​' || c == '‌' || c == '‍' || c == '﻿'
                || c == '⁠' || c == '‮';
    }
}
