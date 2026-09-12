package com.omniforge.tools.python.jep;

import com.omniforge.tools.python.PythonEngine;
import com.omniforge.tools.python.PythonResult;
import jep.JepConfig;
import jep.JepException;
import jep.SharedInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JEP 引擎实现：通过 JNI 进程内嵌入 CPython（需求 4.5）。
 *
 * <p>超时说明：JEP 的 C 层执行无法被 Java 线程中断强制停止，
 * 超时策略为"尽力而为"——立即向调用方返回超时并置 abort 标志，
 * 挂起执行在 C 层跑完后由执行线程关闭解释器，下次执行自动重建。</p>
 *
 * <p>线程模型（JEP 4.x 硬约束）：JEP 实例绑定创建它的线程
 * （{@code Jep.isValidThread()} 校验），且共享解释器非线程安全。
 * 因此全部 JEP 操作（创建/exec/close）串行于同一平台线程
 * （JNI 栈要求平台线程），由单线程执行器保证。</p>
 *
 * <p>流重定向（JEP 4.x 实际 API）：4.x 无 {@code setOut/setErr}，
 * stdout/stderr 重定向经 {@link JepConfig#redirectStdout}/{@code redirectStdErr}
 * 在解释器初始化时一次性绑定（SharedInterpreter 的 config 为进程级静态，
 * 须在首个解释器创建前设置）。绑定的流对象为可切换委托，
 * 每次执行前切换至本次捕获缓冲，实现"每调用独立捕获"。</p>
 */
public class JepPythonEngine implements PythonEngine {

    private static final Logger log = LoggerFactory.getLogger(JepPythonEngine.class);

    /** JEP 重定向绑定的可切换流：初始化时绑定一次，每次执行前切换委托目标 */
    private static final SwitchableOutputStream REDIRECT_OUT = new SwitchableOutputStream();
    private static final SwitchableOutputStream REDIRECT_ERR = new SwitchableOutputStream();

    static {
        try {
            SharedInterpreter.setConfig(new JepConfig()
                    .redirectStdout(REDIRECT_OUT)
                    .redirectStdErr(REDIRECT_ERR));
        } catch (JepException e) {
            // 进程内已有 SharedInterpreter 初始化时 setConfig 会失败（多引擎实例场景不存在），
            // 此时输出落到 JVM 默认 stdout，引擎仍可用
            log.warn("JEP 流重定向配置失败：{}", e.getMessage());
        }
    }

    /** 全部 JEP 调用串行于同一平台线程（JEP 线程绑定约束 + JNI 栈要求） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("omniforge-jep", 0).factory());

    private volatile SharedInterpreter interpreter;

    /** abort 标志：超时/中断后置位；挂起执行返回后由执行线程关闭解释器，下次调用重建 */
    private volatile boolean aborted;

    @Override
    public boolean isAvailable() {
        try {
            // 解释器创建绑定线程，必须在执行器线程上完成
            return executor.submit(this::interpreter).get(5, TimeUnit.SECONDS) != null;
        } catch (Exception e) {
            log.warn("JEP 引擎不可用（请确认本机已安装 CPython 且 JEP 原生库可加载）：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public String version() {
        return "JEP 4.1.1 (CPython embedded)";
    }

    @Override
    public PythonResult execute(String code, long timeoutMillis) {
        long start = System.nanoTime();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Future<?> future = executor.submit(() -> runCode(code, stdout, stderr));
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            String out = stdout.toString(StandardCharsets.UTF_8);
            String err = stderr.toString(StandardCharsets.UTF_8);
            return err.isBlank()
                    ? PythonResult.success(out, elapsedMs(start))
                    : PythonResult.failure(err, elapsedMs(start));
        } catch (TimeoutException e) {
            aborted = true;
            return PythonResult.timeout("执行超时（>" + timeoutMillis + "ms），已尽力终止", elapsedMs(start));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return PythonResult.failure("Python 执行异常: " + cause.getMessage(), elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aborted = true;
            return PythonResult.timeout("执行被中断", elapsedMs(start));
        }
    }

    private void runCode(String code, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        SharedInterpreter si = interpreter();
        REDIRECT_OUT.redirect(stdout);
        REDIRECT_ERR.redirect(stderr);
        try {
            si.exec(code);
        } finally {
            REDIRECT_OUT.redirect(null);
            REDIRECT_ERR.redirect(null);
            if (aborted) {
                // 挂起的执行已返回，在执行器线程上合法关闭解释器；下次调用经 interpreter() 重建
                closeQuietly(si);
                interpreter = null;
                aborted = false;
            }
        }
    }

    /** 仅可在执行器线程调用：abort 后先关闭旧解释器再重建 */
    private SharedInterpreter interpreter() {
        SharedInterpreter current = interpreter;
        if (current == null || aborted) {
            if (current != null) {
                closeQuietly(current);
            }
            current = new SharedInterpreter();
            interpreter = current;
            aborted = false;
        }
        return current;
    }

    private static void closeQuietly(SharedInterpreter si) {
        try {
            si.close();
        } catch (Exception e) {
            log.debug("关闭 JEP 解释器失败：{}", e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 可切换委托的 {@link OutputStream}：JEP 初始化时绑定一次，
     * 委托经 {@link #redirect} 热切换，实现每次执行捕获各自输出。
     */
    private static final class SwitchableOutputStream extends OutputStream {

        private volatile OutputStream delegate;

        void redirect(OutputStream target) {
            this.delegate = target;
        }

        @Override
        public void write(int b) throws IOException {
            OutputStream d = delegate;
            if (d != null) {
                d.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            OutputStream d = delegate;
            if (d != null) {
                d.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            OutputStream d = delegate;
            if (d != null) {
                d.flush();
            }
        }
    }
}
