package com.omniforge.ui.branding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** branding.yml 持久化与归一化测试 */
class BrandingSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final BrandingSettingsStore store = new BrandingSettingsStore();

    @Test
    void 文件缺失时返回默认官方品牌() {
        BrandingSettings settings = store.load(tempDir.resolve("branding.yml"));
        assertThat(settings.appName()).isEqualTo("OmniForge");
        assertThat(settings.logoPath()).isEmpty();
        assertThat(settings.themeColor()).isEmpty();
    }

    @Test
    void 完整配置往返一致() throws Exception {
        Path file = tempDir.resolve("branding.yml");
        Files.writeString(file, "appName: 张三工作台\nlogoPath: C:/logo.png\nthemeColor: \"#10B981\"\n");
        BrandingSettings settings = store.load(file);
        assertThat(settings.appName()).isEqualTo("张三工作台");
        assertThat(settings.logoPath()).isEqualTo("C:/logo.png");
        assertThat(settings.themeColor()).isEqualTo("#10B981");
    }

    @Test
    void 文件损坏时回退默认值() throws Exception {
        Path file = tempDir.resolve("branding.yml");
        Files.writeString(file, "not: [valid");
        assertThat(store.load(file).appName()).isEqualTo("OmniForge");
    }

    @Test
    void 空名称回退默认且名称去空白() {
        assertThat(new BrandingSettings("   ", "", "").appName()).isEqualTo("OmniForge");
        assertThat(new BrandingSettings(" 我的应用 ", "", "").appName()).isEqualTo("我的应用");
    }

    @Test
    void 主题色归一化仅接受六位十六进制() {
        assertThat(BrandingSettings.normalizeThemeColor("#ff0000")).isEqualTo("#FF0000");
        assertThat(BrandingSettings.normalizeThemeColor("#a1b2c3")).isEqualTo("#A1B2C3");
        assertThat(BrandingSettings.normalizeThemeColor("red")).isEqualTo(BrandingSettings.DEFAULT_THEME_COLOR);
        assertThat(BrandingSettings.normalizeThemeColor("#12345")).isEqualTo(BrandingSettings.DEFAULT_THEME_COLOR);
        assertThat(BrandingSettings.normalizeThemeColor("")).isEmpty();
        assertThat(BrandingSettings.normalizeThemeColor(null)).isEmpty();
    }

    @Test
    void 主题色覆盖样式表内容与hover加深() {
        String css = BrandingManager.themeColorCss("#FFFFFF");
        assertThat(css).contains("-of-brand: #FFFFFF;").contains("-of-brand-hover: #e5e5e5;");
    }

    @Test
    void 保存后重新读取一致() throws Exception {
        Path file = tempDir.resolve("branding.yml");
        store.save(file, new BrandingSettings("品牌甲", "D:/a.png", "#112233"));
        BrandingSettings loaded = store.load(file);
        assertThat(loaded.appName()).isEqualTo("品牌甲");
        assertThat(loaded.logoPath()).isEqualTo("D:/a.png");
        assertThat(loaded.themeColor()).isEqualTo("#112233");
    }
}
