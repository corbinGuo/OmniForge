package com.omniforge.common.spi;

import java.time.LocalDateTime;

/**
 * 数据保留清理目标（DATA_RETENTION A3）：一类数据源在某个时间划线前的清理实现。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>实现类自行划线与删除边界（避免 target 间数据耦合），返回删除条目数；</li>
 *   <li>划线时间之前的数据删除，之后保留；</li>
 *   <li>任何 target 异常由调度方隔离（单个失败不中断其余 target）。</li>
 * </ul>
 */
public interface RetentionTarget {

    /** 目标名称（日志展示用，如 session / im / tool-log / audit） */
    String name();

    /**
     * 清理该目标在 {@code before} 之前的旧数据。
     *
     * @param before 时间划线（早于该时刻的数据删除，含边界语义由实现自定）
     * @return 删除的条目数（文件为删除文件数）
     */
    int cleanup(LocalDateTime before);
}
