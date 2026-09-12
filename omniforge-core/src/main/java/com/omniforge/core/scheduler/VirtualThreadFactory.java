package com.omniforge.core.scheduler;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * 虚拟线程统一工厂（需求 9.3）。线程名格式：{prefix}-{序号}，便于日志与诊断。
 *
 * <p>注意：虚拟线程始终为守护线程、优先级固定、无 ThreadLocal 清理成本，
 * 适合承载阻塞型 IO 任务（模型调用、IM 收发）。</p>
 */
public final class VirtualThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;

    /** @param prefix 线程名前缀（如 "omniforge-model"） */
    public VirtualThreadFactory(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        this.delegate = Thread.ofVirtual().name(prefix, 0).factory();
    }

    @Override
    public Thread newThread(Runnable task) {
        return delegate.newThread(Objects.requireNonNull(task, "task"));
    }
}
