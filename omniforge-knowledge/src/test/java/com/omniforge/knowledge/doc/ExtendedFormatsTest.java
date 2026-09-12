package com.omniforge.knowledge.doc;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库文件格式扩展（2026-09-04）解析器测试：
 * Excel(xlsx/xls)、PPT(pptx)、HTML、RTF、GBK 编码文本兜底。
 * (.doc/.ppt 二进制不易程序化构造，仅以 supports 负例覆盖；真机验收补齐)
 */
class ExtendedFormatsTest {

    @TempDir
    Path tempDir;

    @Test
    void xlsx按单元格提取文本() throws IOException {
        Path file = tempDir.resolve("台账.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("台账");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("项目");
            head.createCell(1).setCellValue("金额");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("椅子");
            row1.createCell(1).setCellValue(20);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }

        OfficeExcelParser parser = new OfficeExcelParser();
        assertTrue(parser.supports("a.xlsx"));
        assertTrue(parser.supports("A.XLS"));
        String text = parser.parse(file);
        assertTrue(text.contains("台账"));
        assertTrue(text.contains("项目"));
        assertTrue(text.contains("椅子"));
        assertTrue(text.contains("20"));
    }

    @Test
    void xls旧版Excel提取文本() throws IOException {
        Path file = tempDir.resolve("legacy.xls");
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("旧表");
            sheet.createRow(0).createCell(0).setCellValue("旧版Excel单元格");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }

        OfficeExcelParser parser = new OfficeExcelParser();
        assertTrue(parser.supports("legacy.xls"));
        assertTrue(parser.parse(file).contains("旧版Excel单元格"));
    }

    @Test
    void pptx提取幻灯片文本() throws IOException {
        Path file = tempDir.resolve("deck.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("演示文稿标题");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                ppt.write(out);
            }
        }

        PptxParser parser = new PptxParser();
        assertTrue(parser.supports("deck.pptx"));
        String text = parser.parse(file);
        assertTrue(text.contains("【幻灯片 1】"));
        assertTrue(text.contains("演示文稿标题"));
    }

    @Test
    void html去标签与实体解码() throws IOException {
        Path file = tempDir.resolve("page.html");
        Files.writeString(file, "<html><head><title>t</title></head><body>"
                + "<script>var x = 1;</script><p>你好 &amp; 世界 &#20840;</p></body></html>");

        HtmlParser parser = new HtmlParser();
        assertTrue(parser.supports("page.html"));
        assertTrue(parser.supports("page.htm"));
        String text = parser.parse(file);
        assertTrue(text.contains("你好"));
        assertTrue(text.contains("世界"));
        assertTrue(text.contains("全"), "数字实体 &#20840; 应解码为「全」，实际: " + text);
        assertFalse(text.contains("script"));
    }

    @Test
    void rtf读取正文() throws IOException {
        Path file = tempDir.resolve("note.rtf");
        Files.writeString(file, "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}} \\f0 "
                + "Hello \\b bold\\b0  world\\par second}");

        RtfParser parser = new RtfParser();
        assertTrue(parser.supports("note.rtf"));
        String text = parser.parse(file);
        assertTrue(text.contains("Hello"), "RTF 正文应含 Hello，实际: " + text);
        assertTrue(text.contains("second"));
    }

    @Test
    void gbk编码文本自动兜底解码() throws IOException {
        Path file = tempDir.resolve("gbk.txt");
        Files.write(file, "报销金额明细".getBytes(Charset.forName("GBK")));

        PlainTextParser parser = new PlainTextParser();
        assertTrue(parser.supports("gbk.txt"));
        String text = parser.parse(file);
        assertTrue(text.contains("报销金额"), "GBK 应兜底解码，实际: " + text);
    }

    @Test
    void 解析器支持边界互斥() {
        // html/rtf 不属于纯文本；Office 扩展不属于纯文本/HTML
        PlainTextParser plain = new PlainTextParser();
        assertFalse(plain.supports("a.html"));
        assertFalse(plain.supports("a.rtf"));
        assertTrue(plain.supports("a.csv"));
        assertTrue(plain.supports("a.java"));
        assertFalse(plain.supports("a.xlsx"));

        assertFalse(new OfficeExcelParser().supports("a.docx"));
        assertFalse(new HtmlParser().supports("a.html_evil"));

        // 旧版二进制解析器仅按扩展名判定（是否有效文件由真机验收）
        assertTrue(new WordLegacyParser().supports("a.doc"));
        assertTrue(new PptLegacyParser().supports("a.ppt"));
        assertFalse(new PptxParser().supports("a.ppt"));
        assertFalse(new PptLegacyParser().supports("a.pptx"));
    }
}
