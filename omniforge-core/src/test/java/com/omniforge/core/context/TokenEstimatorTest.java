package com.omniforge.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void 空串与null估算为0() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate("   "));
    }

    @Test
    void 中文按每字一token估算() {
        // 4 字 ×1 ×1.15 = 4.6 → 向上取整 5
        assertEquals(5, TokenEstimator.estimate("你好世界"));
    }

    @Test
    void 英文按每四字符一token估算() {
        // hello(5) + 空格(0.5) + world(5)：5/4 + 0.5 + 5/4 = 3.0 ×1.15 = 3.45 → 4
        assertEquals(4, TokenEstimator.estimate("hello world"));
    }

    @Test
    void 混合内容分别计权() {
        // "你好AI"：2 个中文 + AI 2 字符（0.5 token）→ 2.5 ×1.15 = 2.875 → 3
        assertEquals(3, TokenEstimator.estimate("你好AI"));
    }

    @Test
    void 非空结果至少为1() {
        // 单个字符：0.5（标点）×1.15 = 0.575 → 至少 1
        assertEquals(1, TokenEstimator.estimate("。"));
        assertEquals(1, TokenEstimator.estimate("a"));
    }

    @Test
    void 中文比英文同长度估算更高() {
        assertTrue(TokenEstimator.estimate("你好世界你好世界") > TokenEstimator.estimate("hello world"));
    }
}
