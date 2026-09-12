package com.omniforge.knowledge.doc;

import java.util.List;

/**
 * 文档解析器统一清单（知识库文件格式扩展 2026-09-04）：
 * Spring 装配与 ServiceLoader/工具路径共用同一清单，避免两处扩展名漂移。
 *
 * <p>解析器扩展名两两不相交（PlainTextParser 不含 htm/html/rtf/Office 扩展），
 * 顺序无影响。不支持（无文本层）：图片/扫描件见 {@code KnowledgeService} 提示。</p>
 */
public final class DocumentParsers {

    private DocumentParsers() {
    }

    public static List<DocumentParser> defaults() {
        return List.of(
                new PlainTextParser(),      // txt/md/csv/json/yml/ini/... + 源码/配置文本
                new HtmlParser(),           // htm/html
                new RtfParser(),            // rtf
                new PdfParser(),            // pdf
                new DocxParser(),           // docx
                new WordLegacyParser(),     // doc
                new OfficeExcelParser(),    // xlsx/xls
                new PptxParser(),           // pptx
                new PptLegacyParser());     // ppt
    }
}
