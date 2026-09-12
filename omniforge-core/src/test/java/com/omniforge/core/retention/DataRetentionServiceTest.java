package com.omniforge.core.retention;

import com.omniforge.common.spi.RetentionTarget;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据保留调度（DATA_RETENTION Q1-A/Q3-A）单测：enabled 门控/聚合/单 target 隔离。 */
class DataRetentionServiceTest {

    private final RetentionSettingsHolder holder = new RetentionSettingsHolder(null);

    private RetentionTarget target(String name, int removed) {
        RetentionTarget t = mock(RetentionTarget.class);
        when(t.name()).thenReturn(name);
        when(t.cleanup(any())).thenReturn(removed);
        return t;
    }

    private RetentionTarget explodingTarget(String name) {
        RetentionTarget t = mock(RetentionTarget.class);
        when(t.name()).thenReturn(name);
        when(t.cleanup(any())).thenThrow(new IllegalStateException("boom"));
        return t;
    }

    @Test
    void enabled为false时零调用() {
        holder.update(RetentionSettings.defaults()); // enabled=false
        RetentionTarget t = target("session", 5);
        DataRetentionService service = new DataRetentionService(holder, List.of(t));

        String summary = service.runOnce();

        assertEquals("", summary);
        verify(t, never()).cleanup(any());
    }

    @Test
    void enabled时聚合各target删除数() {
        holder.update(new RetentionSettings(true, 90, 90, 180));
        DataRetentionService service = new DataRetentionService(holder,
                List.of(target("session", 5), target("im", 3), target("audit", 2)));

        String summary = service.runOnce();

        assertTrue(summary.contains("session 5"));
        assertTrue(summary.contains("im 3"));
        assertTrue(summary.contains("audit 2"));
    }

    @Test
    void 单target抛异常不影响其余() {
        holder.update(new RetentionSettings(true, 90, 90, 180));
        RetentionTarget ok = target("session", 5);
        DataRetentionService service = new DataRetentionService(holder,
                List.of(explodingTarget("im"), ok));

        String summary = service.runOnce();

        // 失败的 im 不写入摘要，但 session 正常完成（异常隔离约定）
        assertTrue(summary.contains("session 5"));
        assertTrue(!summary.contains("im 3"));
    }
}
