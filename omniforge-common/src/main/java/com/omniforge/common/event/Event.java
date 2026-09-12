package com.omniforge.common.event;

import java.time.Instant;

/** 事件基接口：所有事件都携带发生时间与来源。 */
public interface Event {

    /** 事件发生时间 */
    Instant occurredAt();

    /** 事件来源（如模块名 "core.debate"） */
    String source();
}
