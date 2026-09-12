package com.omniforge.common.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleEventBusTest {

    private SimpleEventBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
    }

    @Test
    void 发布事件后异步送达监听器() throws InterruptedException {
        bus = new SimpleEventBus();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(TestEvent.class, e -> {
            received.set(e.payload());
            latch.countDown();
        });

        bus.publish(new TestEvent(42));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "监听器应在超时前收到事件");
        assertEquals(42, received.get());
    }

    @Test
    void 监听器异常不影响其他监听器() throws InterruptedException {
        bus = new SimpleEventBus();
        CountDownLatch latch = new CountDownLatch(1);
        bus.subscribe(TestEvent.class, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(TestEvent.class, e -> latch.countDown());

        bus.publish(new TestEvent(1));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "异常监听器不应影响其他监听器");
    }

    @Test
    void 取消订阅后不再送达() throws InterruptedException {
        bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        EventSubscription subscription = bus.subscribe(TestEvent.class, e -> received.incrementAndGet());
        subscription.unsubscribe();
        subscription.unsubscribe(); // 幂等

        bus.publish(new TestEvent(1));
        Thread.sleep(300); // 等待异步分发窗口

        assertEquals(0, received.get());
    }

    @Test
    void 不同类型事件互不干扰() throws InterruptedException {
        bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(TestEvent.class, e -> received.incrementAndGet());
        bus.subscribe(OtherEvent.class, e -> received.addAndGet(100));

        bus.publish(new OtherEvent());
        Thread.sleep(300);

        assertEquals(100, received.get());
    }

    record TestEvent(int payload) implements Event {
        @Override
        public Instant occurredAt() {
            return Instant.now();
        }

        @Override
        public String source() {
            return "test";
        }
    }

    record OtherEvent() implements Event {
        @Override
        public Instant occurredAt() {
            return Instant.now();
        }

        @Override
        public String source() {
            return "test";
        }
    }
}
