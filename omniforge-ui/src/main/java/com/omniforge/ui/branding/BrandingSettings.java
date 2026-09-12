package com.omniforge.ui.branding;

/**
 * 白标设置（branding.yml，C-tier 批次 4-1）。
 *
 * @param appName    应用名称（窗口标题/导航栏 Logo/关于页面）
 * @param logoPath   Logo 图片路径（png/jpg，缺失或空时用文字 Logo「◆ 名称」）
 * @param themeColor 主题色（#RRGGBB，空 = 默认品牌蓝 #2563EB）
 */
public record BrandingSettings(String appName, String logoPath, String themeColor) {

    public static final String DEFAULT_APP_NAME = "OmniForge";
    public static final String DEFAULT_THEME_COLOR = "#2563EB";

    public BrandingSettings {
        appName = appName == null || appName.isBlank() ? DEFAULT_APP_NAME : appName.trim();
        logoPath = logoPath == null ? "" : logoPath.trim();
        themeColor = normalizeThemeColor(themeColor);
    }

    /** 默认白标：官方品牌 */
    public static BrandingSettings defaults() {
        return new BrandingSettings(DEFAULT_APP_NAME, "", "");
    }

    /** 主题色归一化：仅接受 #RRGGBB（六位十六进制，大小写均可），其余回退默认 */
    public static String normalizeThemeColor(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.matches("(?i)^#[0-9a-f]{6}$")) {
            return value.toUpperCase();
        }
        return value.isEmpty() ? "" : DEFAULT_THEME_COLOR;
    }
}
