package com.omniforge.tools.python.jep;

import com.omniforge.tools.python.PythonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JepPythonEngine 真机测试：JEP 原生库可用时执行（Linux conda-forge / Windows 源码编译安装后），
 * 不可用时整体跳过（Windows 上 JEP 无预编译包，需本机 MSVC 编译或改用 Linux 部署）。
 */
class JepPythonEngineTest {

    private JepPythonEngine engine;

    @BeforeEach
    void setUp() {
        engine = new JepPythonEngine();
        assumeTrue(engine.isAvailable(), "JEP 原生库不可用，跳过真机测试");
    }

    @Test
    void 基本执行捕获标准输出() {
        PythonResult result = engine.execute("print('hello jep')", 5000);
        assertThat(result.status()).isEqualTo(PythonResult.Status.SUCCESS);
        assertThat(result.output()).contains("hello jep");
        assertThat(result.error()).isEmpty();
    }

    @Test
    void 标准错误输出判为失败() {
        PythonResult result = engine.execute("import sys; sys.stderr.write('boom')", 5000);
        assertThat(result.status()).isEqualTo(PythonResult.Status.ERROR);
        assertThat(result.error()).contains("boom");
    }

    @Test
    void Python异常映射为失败() {
        PythonResult result = engine.execute("raise ValueError('bad input')", 5000);
        assertThat(result.status()).isEqualTo(PythonResult.Status.ERROR);
        assertThat(result.error()).contains("ValueError");
    }

    @Test
    void 超时执行返回超时且引擎可继续使用() {
        PythonResult timedOut = engine.execute("import time; time.sleep(30)", 1000);
        assertThat(timedOut.status()).isEqualTo(PythonResult.Status.TIMEOUT);

        // 挂起执行返回后解释器重建，引擎仍可正常执行
        PythonResult followUp = engine.execute("print('recovered')", 5000);
        assertThat(followUp.status()).isEqualTo(PythonResult.Status.SUCCESS);
        assertThat(followUp.output()).contains("recovered");
    }

    @Test
    void 连续执行输出互不串扰() {
        PythonResult first = engine.execute("print('first-output')", 5000);
        PythonResult second = engine.execute("print('second-output')", 5000);
        assertThat(first.output()).contains("first-output").doesNotContain("second-output");
        assertThat(second.output()).contains("second-output").doesNotContain("first-output");
    }
}
