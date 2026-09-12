package com.omniforge.common.event;

/**
 * 事件订阅句柄。调用 {@link #unsubscribe()} 取消订阅（幂等，重复调用安全）。
 */
public final class EventSubscription {

    private final Runnable unsubscribeAction;
    private volatile boolean unsubscribed = false;

    EventSubscription(Runnable unsubscribeAction) {
        this.unsubscribeAction = unsubscribeAction;
    }

    /** 取消订阅；重复调用无副作用 */
    public void unsubscribe() {
        if (!unsubscribed) {
            unsubscribed = true;
            unsubscribeAction.run();
        }
    }

    /** 是否已取消订阅 */
    public boolean isUnsubscribed() {
        return unsubscribed;
    }
}
