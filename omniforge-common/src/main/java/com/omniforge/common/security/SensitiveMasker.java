package com.omniforge.common.security;

/**
 * 敏感信息掩码工具。
 *
 * <p>合规底线（需求 9.2 / 开发指令）：严禁在日志中明文记录 API Key 或用户敏感信息，
 * 输出前一律经本工具脱敏。</p>
 */
public final class SensitiveMasker {

    private static final int API_KEY_PREFIX = 4;
    private static final int API_KEY_SUFFIX = 4;
    private static final int MASK_THRESHOLD = 10;

    private SensitiveMasker() {
    }

    /**
     * 掩码 API Key：保留前 4 后 4 字符（如 sk-a****9f2e）；长度 ≤10 时全掩。
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        if (apiKey.length() <= MASK_THRESHOLD) {
            return "*".repeat(apiKey.length());
        }
        return apiKey.substring(0, API_KEY_PREFIX)
                + "****"
                + apiKey.substring(apiKey.length() - API_KEY_SUFFIX);
    }

    /**
     * 掩码任意敏感串：保留首尾各 2 字符，其余以 * 替代；长度 ≤4 时全掩。
     */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }
}
