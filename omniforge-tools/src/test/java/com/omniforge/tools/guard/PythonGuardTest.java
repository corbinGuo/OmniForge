package com.omniforge.tools.guard;

import com.omniforge.tools.ToolsProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PythonGuardTest {

    @Test
    void 危险语句被拦截() {
        List<java.util.regex.Pattern> patterns = new ToolsProperties().pythonBlacklistPatterns();
        assertNotNull(PythonGuard.findViolation("import os\nos.system('rm -rf /')", patterns));
        assertNotNull(PythonGuard.findViolation("import subprocess\nsubprocess.run('ls')", patterns));
        assertNotNull(PythonGuard.findViolation("eval('1+1')", patterns));
        assertNotNull(PythonGuard.findViolation("__import__('os')", patterns));
    }

    @Test
    void 普通代码放行() {
        List<java.util.regex.Pattern> patterns = new ToolsProperties().pythonBlacklistPatterns();
        assertNull(PythonGuard.findViolation("print('hello')", patterns));
        assertNull(PythonGuard.findViolation("import os\nprint(os.name)", patterns), "仅 import 不触发拦截");
        assertNull(PythonGuard.findViolation("x = [i*i for i in range(10)]", patterns));
    }

    @Test
    void 空代码放行() {
        List<java.util.regex.Pattern> patterns = new ToolsProperties().pythonBlacklistPatterns();
        assertNull(PythonGuard.findViolation(null, patterns));
        assertNull(PythonGuard.findViolation("", patterns));
    }
}
