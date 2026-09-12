package com.omniforge.knowledge.doc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML/HTM 解析器（知识库文件格式扩展 2026-09-04，无第三方依赖）。
 * 去除 script/style/注释与标签后取正文文本，常用实体与数字实体解码。
 */
public final class HtmlParser implements DocumentParser {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b[^>]*>.*?</style>");
    private static final Pattern COMMENT = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("(?i)&#x([0-9a-f]+);");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\r\\n]{2,}");

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".htm") || lower.endsWith(".html");
    }

    @Override
    public String parse(Path file) throws IOException {
        return toText(TextDecoding.read(file));
    }

    @Override
    public String name() {
        return "HTML 解析器（内置）";
    }

    /** 去脚本/样式/注释 → 剥标签 → 实体解码 → 归一空白 */
    static String toText(String html) {
        String text = COMMENT.matcher(SCRIPT.matcher(STYLE.matcher(html).replaceAll(" ")).replaceAll(" "))
                .replaceAll(" ");
        text = TAGS.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        Matcher numeric = NUM_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (numeric.find()) {
            numeric.appendReplacement(sb, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(numeric.group(1)))));
        }
        numeric.appendTail(sb);
        text = sb.toString();
        Matcher hex = HEX_ENTITY.matcher(text);
        StringBuilder hexSb = new StringBuilder(text.length());
        while (hex.find()) {
            hex.appendReplacement(hexSb, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(hex.group(1), 16))));
        }
        hex.appendTail(hexSb);
        return MULTI_SPACE.matcher(hexSb.toString()).replaceAll(" ").trim();
    }
}
