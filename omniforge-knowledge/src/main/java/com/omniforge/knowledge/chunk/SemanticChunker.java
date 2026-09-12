package com.omniforge.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义分块器（需求 4.4：语义分块 + 重叠窗口 Overlap=10%）。
 *
 * <p>策略：按句子边界（。！？!?；;\n）切分，逐句合并至目标长度；
 * 每个切片的前缀追加上一片段的尾部 10%（重叠窗口），保证检索上下文连续。</p>
 */
public final class SemanticChunker {

    /** 句子边界（保留分隔符） */
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？!?；;\\n])|(?=[。！？!?；;\\n])");

    private final int maxChars;
    private final double overlapRatio;

    /**
     * @param maxChars     目标切片长度（字符，默认 1024 ≈ 512 token）
     * @param overlapRatio 重叠比例（0~0.5，默认 0.1）
     */
    public SemanticChunker(int maxChars, double overlapRatio) {
        if (maxChars < 128) {
            throw new IllegalArgumentException("maxChars must be >= 128");
        }
        if (overlapRatio < 0 || overlapRatio > 0.5) {
            throw new IllegalArgumentException("overlapRatio must be in [0, 0.5]");
        }
        this.maxChars = maxChars;
        this.overlapRatio = overlapRatio;
    }

    public SemanticChunker() {
        this(1024, 0.1);
    }

    /** 将整篇文本切分为有序切片（内容无丢失，含重叠） */
    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = splitSentences(text);
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkStart = 0;
        String previousTail = "";

        for (String sentence : sentences) {
            if (current.length() + sentence.length() > maxChars && current.length() > 0) {
                chunks.add(new TextChunk(chunks.size(), current.toString(), chunkStart,
                        chunkStart + current.length()));
                previousTail = tail(current.toString());
                chunkStart += current.length() - previousTail.length();
                current = new StringBuilder(previousTail);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(new TextChunk(chunks.size(), current.toString(), chunkStart,
                    chunkStart + current.length()));
        }
        return chunks;
    }

    private String tail(String content) {
        if (overlapRatio <= 0) {
            return "";
        }
        int overlapChars = (int) Math.min(content.length(), maxChars * overlapRatio);
        // 从句子边界开始重叠，避免截断半句
        String tailPart = content.substring(content.length() - overlapChars);
        int boundary = firstSentenceBoundary(tailPart);
        return boundary > 0 ? tailPart.substring(boundary) : tailPart;
    }

    private static int firstSentenceBoundary(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : SENTENCE_BOUNDARY.split(text)) {
            current.append(part);
            if (part.matches(".*[。！？!?；;\\n]$")) {
                sentences.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }
}
