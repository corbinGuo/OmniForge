package com.omniforge.ui.branding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** BrandingManager.reload()（P2-1 运营中心品牌保存即生效）测试 */
class BrandingReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void 保存主题色后reload重建样式表并更新应用名() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("cfg"));
        new BrandingSettingsStore().save(dir.resolve("branding.yml"),
                new BrandingSettings("我的工作台", "", "#0F766E"));

        BrandingManager.reload(dir);

        assertThat(BrandingManager.appName()).isEqualTo("我的工作台");
        assertThat(BrandingManager.themeColorOverrideCssFile()).isNotNull();
        assertThat(Files.readString(BrandingManager.themeColorOverrideCssFile()))
                .contains("-of-brand: #0F766E;");
    }

    @Test
    void 清除主题色后reload删除覆盖样式表() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("cfg2"));
        new BrandingSettingsStore().save(dir.resolve("branding.yml"), new BrandingSettings("x", "", ""));

        BrandingManager.reload(dir);

        assertThat(BrandingManager.themeColorOverrideCssFile()).isNull();
    }
}
