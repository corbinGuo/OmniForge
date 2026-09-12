package com.omniforge.knowledge.doc;

import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Word（.doc 旧版二进制）解析器（Apache POI HWPF/poi-scratchpad，知识库文件格式扩展 2026-09-04）。
 */
public final class WordLegacyParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".doc");
    }

    @Override
    public String parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             WordExtractor extractor = new WordExtractor(in)) {
            String text = extractor.getText();
            return text == null ? "" : text;
        } catch (Exception e) {
            // 非 OLE/损坏文件会抛运行时异常（NotPOIFSFileException 等），统一包装为 IOException
            throw new IOException("Word(.doc) 解析失败（可能不是有效的二进制 Word 文档）: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "POI Word(.doc) 解析器";
    }
}
