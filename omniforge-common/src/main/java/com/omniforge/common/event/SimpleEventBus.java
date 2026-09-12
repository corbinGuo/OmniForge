package com.omniforge.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 基于虚拟线程的事件总线实现。
 *
 * <p>监听器在虚拟线程中异步执行（"尽力而为"语义），监听器异常被隔离并仅记录告警日志。
 * 订阅表使用 CopyOnWrite 结构，支持发布期间并发订阅/退订。</p>
 */
public final class SimpleEventBus implements EventBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SimpleEventBus.class);

    private final ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<ListenerWrapper<?>>> listeners =
            new ConcurrentHashMap<>();
    private final ExecutorService executor;

    /** 使用默认虚拟线程执行器（线程名 omniforge-event-*） */
    public SimpleEventBus() {
        this(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("omniforge-event-", 0).factory()));
    }

    /** 使用调用方提供的执行器（测试中可注入同步执行器） */
    public SimpleEventBus(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public <T extends Event> EventSubscription subscribe(Class<T> eventType, Consumer<T> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        ListenerWrapper<T> wrapper = new ListenerWrapper<>(eventType, listener);
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(wrapper);
        return new EventSubscription(() ->
                listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).remove(wrapper));
    }

    @Override
    public void publish(Event event) {
        Objects.requireNonNull(event, "event");
        CopyOnWriteArrayList<ListenerWrapper<?>> matches = listeners.get(event.getClass());
        if (matches == null || matches.isEmpty()) {
            return;
        }
        for (ListenerWrapper<?> wrapper : matches) {
            try {
                executor.execute(() -> wrapper.deliver(event));
            } catch (RejectedExecutionException e) {
                log.warn("事件总线已关闭，事件被丢弃：type={}", event.getClass().getSimpleName());
            }
        }
    }

    /** 关闭执行器：等待已提交的监听任务完成后再退出 */
    @Override
    public void close() {
        executor.close();
    }

    @SuppressWarnings("unchecked")
    private record ListenerWrapper<T extends Event>(Class<T> eventType, Consumer<T> listener) {

        void deliver(Event event) {
            try {
                listener.accept((T) event);
            } catch (Exception e) {
                log.warn("事件监听器执行失败（已隔离）：type={}, listener={}",
                        eventType.getSimpleName(), listener, e);
            }
        }
    }
}
