package com.omniforge.knowledge.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticChunkerTest {

    @Test
    void 短文本单切片() {
        SemanticChunker chunker = new SemanticChunker(1024, 0.1);
        List<TextChunk> chunks = chunker.chunk("第一句。第二句！第三句？");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).content().contains("第三句"));
    }

    @Test
    void 长文本多切片且内容不丢失() {
        SemanticChunker chunker = new SemanticChunker(128, 0.1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句测试内容，用于验证语义分块的正确性。");
        }
        List<TextChunk> chunks = chunker.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "64 字符上限应产生多个切片");

        // 去除重叠后应能还原原文（逐句验证每个句子都出现在某个切片中）
        for (int i = 0; i < 20; i++) {
            String sentence = "这是第" + i + "句测试内容，用于验证语义分块的正确性。";
            assertTrue(chunks.stream().anyMatch(c -> c.content().contains(sentence)),
                    "第 " + i + " 句不应丢失");
        }
    }

    @Test
    void 重叠窗口存在() {
        SemanticChunker chunker = new SemanticChunker(128, 0.1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句测试内容，用于验证语义分块的正确性。");
        }
        List<TextChunk> chunks = chunker.chunk(sb.toString());
        if (chunks.size() > 1) {
            String firstTail = chunks.get(0).content().substring(
                    Math.max(0, chunks.get(0).content().length() - 10));
            assertTrue(chunks.get(1).content().contains(firstTail),
                    "第二切片应以第一切片尾部开头（重叠窗口）");
        }
    }

    @Test
    void 空文本无切片() {
        SemanticChunker chunker = new SemanticChunker();
        assertEquals(0, chunker.chunk("").size());
        assertEquals(0, chunker.chunk(null).size());
    }

    @Test
    void 参数校验() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SemanticChunker(64, 0.1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SemanticChunker(1024, 0.9));
    }
}
