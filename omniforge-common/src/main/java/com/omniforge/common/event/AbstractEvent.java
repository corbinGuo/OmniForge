package com.omniforge.common.event;

import java.time.Instant;
import java.util.Objects;

/** 事件基类：提供 {@link Event#occurredAt()} 与 {@link Event#source()} 的默认实现。 */
public abstract class AbstractEvent implements Event {

    private final Instant occurredAt;
    private final String source;

    protected AbstractEvent(String source) {
        this(Instant.now(), source);
    }

    protected AbstractEvent(Instant occurredAt, String source) {
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    @Override
    public String source() {
        return source;
    }
}
