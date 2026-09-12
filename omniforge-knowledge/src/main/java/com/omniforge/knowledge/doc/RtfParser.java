package com.omniforge.knowledge.doc;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Document;
import javax.swing.text.rtf.RTFEditorKit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RTF 解析器（知识库文件格式扩展 2026-09-04）。
 * 复用 JDK {@link RTFEditorKit}（java.desktop，纯 Java、无第三方依赖）读取富文本正文。
 */
public final class RtfParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".rtf");
    }

    @Override
    public String parse(Path file) throws IOException {
        RTFEditorKit kit = new RTFEditorKit();
        Document doc = kit.createDefaultDocument();
        try {
            try (InputStream in = Files.newInputStream(file)) {
                kit.read(in, doc, 0);
            }
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            throw new IOException("RTF 文本读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "RTF 解析器（JDK RTFEditorKit）";
    }
}
