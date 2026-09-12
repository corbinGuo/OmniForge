package com.omniforge.common.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExponentialBackoffTest {

    @Test
    void 连续失败间隔按二的幂翻倍并封顶() {
        ExponentialBackoff backoff = new ExponentialBackoff(1_000, 8_000, 0);
        assertEquals(2_000, backoff.onFailure(), "第 1 次失败：2× 基础间隔");
        assertEquals(4_000, backoff.onFailure());
        assertEquals(8_000, backoff.onFailure());
        assertEquals(8_000, backoff.onFailure(), "到达上限后保持封顶值");
        assertEquals(8_000, backoff.onFailure());
        assertEquals(5, backoff.consecutiveFailures());
    }

    @Test
    void 成功后复位为基础间隔() {
        ExponentialBackoff backoff = new ExponentialBackoff(1_000, 8_000, 0);
        backoff.onFailure();
        backoff.onFailure();
        assertEquals(2, backoff.consecutiveFailures());

        assertEquals(1_000, backoff.onSuccess(), "成功即复位基础间隔");
        assertEquals(0, backoff.consecutiveFailures());
        assertEquals(2_000, backoff.onFailure(), "复位后重新从第一次失败计");
    }

    @Test
    void 抖动在系数范围内() {
        for (int i = 0; i < 200; i++) {
            // 每次新实例：onFailure 均为第一次失败（原始 2000），便于断言范围
            ExponentialBackoff backoff = new ExponentialBackoff(1_000, 1_000_000, 0.2);
            long delay = backoff.onFailure();
            assertTrue(delay >= 1_600 && delay <= 2_400, "抖动应落在 ±20% 内，实际 " + delay);
        }
    }

    @Test
    void 参数校验() {
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(-1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(5_000, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(1_000, 5_000, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(1_000, 5_000, -0.1));
    }
}
