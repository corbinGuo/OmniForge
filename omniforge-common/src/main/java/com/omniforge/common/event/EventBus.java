package com.omniforge.common.event;

import java.util.function.Consumer;

/**
 * 进程内事件总线。
 *
 * <p>发布采用"尽力而为"语义：监听器异步执行，单个监听器异常不影响其他监听器，
 * 也不会回传给发布方。</p>
 */
public interface EventBus {

    /** 订阅某类事件（精确类型匹配，不含子类），返回可取消订阅的句柄 */
    <T extends Event> EventSubscription subscribe(Class<T> eventType, Consumer<T> listener);

    /** 发布事件：按事件类型分发至所有匹配的监听器（异步执行） */
    void publish(Event event);
}
