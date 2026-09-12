package com.omniforge.tools.python;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Python 执行引擎 SPI（需求 4.5：JEP 通过 JNI 嵌入 CPython，进程内高性能调用 Python）。
 *
 * <p>默认实现位于独立模块 omniforge-tools-python（依赖 JEP，zlib 许可），
 * 经 ServiceLoader 发现；未安装该模块时 python_interpreter 工具优雅降级并给出安装指引。</p>
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>{@link #isAvailable()} 不得抛出异常（原生库/Python 环境缺失时返回 false）；</li>
 *   <li>{@link #execute} 必须尊重超时——超时后应尽力终止底层执行（如关闭解释器）；</li>
 *   <li>实现需线程安全（引擎可能被多个虚拟线程并发调用）。</li>
 * </ul>
 */
public interface PythonEngine {

    /** 执行 Python 代码（阻塞，受超时约束） */
    PythonResult execute(String code, long timeoutMillis);

    /** 引擎是否可用（JEP 原生库 + 本机 Python 环境就绪） */
    boolean isAvailable();

    /** 引擎版本描述（如 "JEP 4.1.1 (CPython embedded)"） */
    String version();

    /** 通过 ServiceLoader 发现可用引擎；未安装时返回 Optional.empty */
    static Optional<PythonEngine> discover() {
        return ServiceLoader.load(PythonEngine.class).findFirst();
    }
}
