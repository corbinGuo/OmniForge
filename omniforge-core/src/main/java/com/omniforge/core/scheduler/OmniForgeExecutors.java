package com.omniforge.core.scheduler;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 虚拟线程执行器工厂：每任务一个虚拟线程（Java 21 原生）。 */
public final class OmniForgeExecutors {

    private OmniForgeExecutors() {
    }

    /**
     * 创建"每任务一虚拟线程"的执行器，线程名以 prefix 开头。
     * 返回的 ExecutorService 使用完毕后应调用 {@link ExecutorService#close()}。
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return Executors.newThreadPerTaskExecutor(new VirtualThreadFactory(prefix));
    }
}
