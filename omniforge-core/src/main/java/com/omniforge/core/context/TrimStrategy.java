package com.omniforge.core.context;

/**
 * 上下文裁剪策略（需求 4.2 上下文管理）。
 *
 * <p>SLIDING_WINDOW：从最旧轮对逐对删除（默认）；
 * SUMMARIZE：滑动窗口 + 被裁部分先经模型摘要为一条 SYSTEM 消息置顶，
 * 摘要调用失败时自动降级为纯滑动窗口。</p>
 */
public enum TrimStrategy {

    SLIDING_WINDOW,
    SUMMARIZE
}
