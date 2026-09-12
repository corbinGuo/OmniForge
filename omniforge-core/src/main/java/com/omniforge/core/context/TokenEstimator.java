package com.omniforge.core.context;

/**
 * Token 数量启发式估算（零依赖，仅用于上下文裁剪决策，不参与成本计量）。
 *
 * <p>规则（社区通用近似 + 保守系数）：
 * <ul>
 *   <li>中文/日文/韩文：≈ 1 token/字（多数 BPE 分词器下 0.6~1，取 1 为保守值）；</li>
 *   <li>拉丁字母/数字：≈ 4 字符/token；</li>
 *   <li>空白与标点：折半计入（0.5）；</li>
 *   <li>结果 ×1.15 保守系数（对分词器估算偏差的方向性补偿），向上取整。</li>
 * </ul>
 *
 * <p>成本计量仍以模型返回的 usage 实际值为准，与本估算互不影响。</p>
 */
public final class TokenEstimator {

    /** 保守系数：对分词器估算偏差的方向性补偿 */
    static final double CONSERVATIVE_FACTOR = 1.15;
    /** 拉丁字符每 token 的字符数近似值 */
    static final double LATIN_CHARS_PER_TOKEN = 4.0;
    /** 空白与标点折半计入 */
    static final double PUNCTUATION_WEIGHT = 0.5;

    private TokenEstimator() {
    }

    /** 估算文本的 token 数；null/空白返回 0 */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double tokens = 0;
        int latinRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                tokens += flush(latinRun);
                latinRun = 0;
                tokens += 1;
            } else if (Character.isLetterOrDigit(c)) {
                latinRun++;
            } else {
                tokens += flush(latinRun);
                latinRun = 0;
                tokens += PUNCTUATION_WEIGHT;
            }
        }
        tokens += flush(latinRun);
        double conservative = Math.ceil(tokens * CONSERVATIVE_FACTOR);
        return (int) Math.max(1, conservative);
    }

    private static double flush(int latinRun) {
        return latinRun / LATIN_CHARS_PER_TOKEN;
    }

    /** CJK 统一表意文字 + 扩展 A 区 + 日文假名 + 谚文 */
    private static boolean isCjk(char c) {
        return (c >= '一' && c <= '鿿')
                || (c >= '㐀' && c <= '䶿')
                || (c >= '぀' && c <= 'ヿ')
                || (c >= '가' && c <= '힯');
    }
}
