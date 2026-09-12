package com.omniforge.tools.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShellGuardTest {

    private static final List<String> BLACKLIST = ShellGuard.DEFAULT_BLACKLIST;

    @Test
    void 危险命令被拦截() {
        assertNotNull(ShellGuard.findViolation("rm -rf /", BLACKLIST));
        assertNotNull(ShellGuard.findViolation("rm  -rf  /", BLACKLIST), "多余空白应被规范化");
        assertNotNull(ShellGuard.findViolation("FORMAT C:", BLACKLIST), "大小写不敏感");
        assertNotNull(ShellGuard.findViolation("shutdown /s /t 0", BLACKLIST));
    }

    @Test
    void 正常命令放行() {
        assertNull(ShellGuard.findViolation("dir", BLACKLIST));
        assertNull(ShellGuard.findViolation("echo hello", BLACKLIST));
        assertNull(ShellGuard.findViolation("python script.py", BLACKLIST));
    }

    @Test
    void 空命令放行() {
        assertNull(ShellGuard.findViolation(" ", BLACKLIST));
        assertNull(ShellGuard.findViolation(null, BLACKLIST));
    }
}
