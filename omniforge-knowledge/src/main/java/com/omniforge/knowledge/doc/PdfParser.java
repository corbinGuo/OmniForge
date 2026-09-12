package com.omniforge.knowledge.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PDF 解析器（Apache PDFBox 3.x，纯 Java，需求 4.4）。
 * 提取全部页面文本；扫描版（无文本层）PDF 返回空串，由上层提示。
 */
public final class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public String parse(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text;
        }
    }

    @Override
    public String name() {
        return "PDFBox PDF 解析器";
    }
}
