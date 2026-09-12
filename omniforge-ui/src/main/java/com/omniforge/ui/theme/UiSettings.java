package com.omniforge.ui.theme;

/**
 * 界面设置（ui.yml）：当前仅含主题（dark/light），为后续界面偏好预留扩展位。
 * 未知/非法主题值一律归一化为 dark（默认），保证文件损坏时界面可用。
 */
public record UiSettings(String theme) {

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    public UiSettings {
        theme = normalize(theme);
    }

    /** 默认设置：暗色主题 */
    public static UiSettings defaults() {
        return new UiSettings(THEME_DARK);
    }

    /** 主题值归一化：仅识别 light，其余（含 null/未知/损坏值）回退 dark */
    public static String normalize(String raw) {
        return THEME_LIGHT.equals(raw) ? THEME_LIGHT : THEME_DARK;
    }
}
