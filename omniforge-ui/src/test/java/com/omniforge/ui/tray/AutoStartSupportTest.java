package com.omniforge.ui.tray;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开机自启（B2）纯函数与判定用例：reg 参数串 / 引号转义 / desktop 内容 /
 * 输出匹配 / 开发模式 unsupported。外部命令真跑不进单测（避免写用户注册表）。
 */
class AutoStartSupportTest {

    private static final String EXE = "C:\\Program Files\\OmniForge\\omniforge.exe";

    @Test
    void reg参数串含引号值数据与强制覆盖() {
        var args = AutoStartSupport.buildRegAddArgs(EXE);
        assertThat(args).containsSequence(
                "reg", "add", AutoStartSupport.WIN_RUN_KEY, "/v", AutoStartSupport.APP_NAME,
                "/t", "REG_SZ", "/d", "\"" + EXE + "\"", "/f");
    }

    @Test
    void reg参数串容忍路径空格与引号转义() {
        var args = AutoStartSupport.buildRegAddArgs("C:\\My Apps\\omni\"forge.exe");
        String data = args.get(args.indexOf("/d") + 1); // /d 的值
        assertThat(data).isEqualTo("\"C:\\My Apps\\omni\\\"forge.exe\"");
    }

    @Test
    void desktop内容含类型名称与带引号Exec() {
        String content = AutoStartSupport.buildDesktopContent("/opt/omniforge/bin/omniforge");
        assertThat(content)
                .contains("[Desktop Entry]")
                .contains("Type=Application")
                .contains("Name=OmniForge")
                .contains("Exec=\"/opt/omniforge/bin/omniforge\"");
    }

    @Test
    void reg输出匹配当前路径且容忍大小写与正反斜杠() {
        String output = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\r\n"
                + "    OmniForge    REG_SZ    \"c:/program files/omniforge/omniforge.exe\"\r\n";
        assertThat(AutoStartSupport.regQueryMatches(output, EXE)).isTrue();
        assertThat(AutoStartSupport.regQueryMatches(output, "D:\\other\\omniforge.exe")).isFalse();
        assertThat(AutoStartSupport.regQueryMatches(null, EXE)).isFalse();
    }

    @Test
    void desktop匹配要求Exec行精确指向() {
        String content = AutoStartSupport.buildDesktopContent(EXE);
        assertThat(AutoStartSupport.desktopMatches(content, EXE)).isTrue();
        assertThat(AutoStartSupport.desktopMatches(content, "C:\\other\\omniforge.exe")).isFalse();
        assertThat(AutoStartSupport.desktopMatches(null, EXE)).isFalse();
    }

    @Test
    void 开发模式无jpackage属性时不受支持() {
        String old = System.getProperty("jpackage.app-path");
        try {
            System.clearProperty("jpackage.app-path");
            assertThat(AutoStartSupport.supported()).isFalse();
            assertThat(AutoStartSupport.launcherPath()).isNull();
            assertThat(AutoStartSupport.isEnabled()).isFalse();
            assertThat(AutoStartSupport.setEnabled(true)).isFalse();
        } finally {
            if (old != null) {
                System.setProperty("jpackage.app-path", old);
            }
        }
    }
}
