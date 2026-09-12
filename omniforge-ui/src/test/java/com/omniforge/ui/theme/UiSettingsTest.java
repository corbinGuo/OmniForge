package com.omniforge.ui.theme;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 主题设置归一化测试：未知/损坏值一律回退暗色，保证界面可用 */
class UiSettingsTest {

    @Test
    void 默认主题为暗色() {
        assertThat(UiSettings.defaults().theme()).isEqualTo(UiSettings.THEME_DARK);
    }

    @Test
    void 合法light值保留() {
        assertThat(UiSettings.normalize("light")).isEqualTo(UiSettings.THEME_LIGHT);
    }

    @Test
    void null与未知值回退暗色() {
        assertThat(UiSettings.normalize(null)).isEqualTo(UiSettings.THEME_DARK);
        assertThat(UiSettings.normalize("")).isEqualTo(UiSettings.THEME_DARK);
        assertThat(UiSettings.normalize("Dark")).isEqualTo(UiSettings.THEME_DARK);
        assertThat(UiSettings.normalize("blue")).isEqualTo(UiSettings.THEME_DARK);
    }

    @Test
    void 构造时自动归一化() {
        assertThat(new UiSettings("light").theme()).isEqualTo(UiSettings.THEME_LIGHT);
        assertThat(new UiSettings("junk").theme()).isEqualTo(UiSettings.THEME_DARK);
        assertThat(new UiSettings(null).theme()).isEqualTo(UiSettings.THEME_DARK);
    }
}
