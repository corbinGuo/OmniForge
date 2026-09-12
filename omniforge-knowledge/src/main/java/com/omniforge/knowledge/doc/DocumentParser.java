package com.omniforge.knowledge.doc;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档解析 SPI（需求 4.4：PDF/Word/TXT/Markdown 自动向量化）。
 *
 * <p>实现要求：单文件解析（线程安全、无状态）；不支持的文件类型返回 false；
 * 解析失败抛 IOException，由上层转换为失败结果。</p>
 */
public interface DocumentParser {

    /** 是否支持该文件（按扩展名/内容判断） */
    boolean supports(String fileName);

    /** 解析为纯文本 */
    String parse(Path file) throws IOException;

    /** 解析器显示名（如 "PDFBox PDF 解析器"） */
    String name();
}
