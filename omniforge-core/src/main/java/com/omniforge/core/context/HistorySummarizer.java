package com.omniforge.core.context;

import java.util.List;

/**
 * 历史摘要器 SPI：把被裁剪掉的历史轮对压缩为一段摘要文本。
 *
 * <p>返回 null/空白表示摘要不可用，调用方自动降级为纯滑动窗口；
 * 抛出的异常由调用方捕获降级（摘要失败绝不中断对话）。</p>
 */
@FunctionalInterface
public interface HistorySummarizer {

    /**
     * 摘要被裁剪的条目。
     *
     * @param dropped 被裁剪掉的历史条目（按时间顺序，user/assistant 成对）
     * @return 摘要文本（≤600 字为宜）；null/空白 = 降级为纯滑动窗口
     */
    String summarize(List<ContextEntry> dropped);
}
