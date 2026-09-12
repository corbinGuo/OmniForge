package com.omniforge.knowledge.chunk;

/** 文本切片（需求 4.4：语义分块 + 重叠窗口）。 */
public record TextChunk(int index, String content, int startChar, int endChar) {

    /** 估算 token 数（中英文混合粗略按 2 字符/token 折算，仅作计量参考） */
    public int estimatedTokens() {
        return Math.max(1, content.length() / 2);
    }
}
