package com.omniforge.knowledge.doc;

import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PowerPoint（.ppt 旧版二进制）解析器（Apache POI HSLF/poi-scratchpad，
 * 知识库文件格式扩展 2026-09-04）。逐幻灯片取文本框内容。
 */
public final class PptLegacyParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".ppt");
    }

    @Override
    public String parse(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
             HSLFSlideShow ppt = new HSLFSlideShow(in)) {
            int index = 0;
            for (HSLFSlide slide : ppt.getSlides()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("【幻灯片 ").append(++index).append('】');
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append('\n').append(text);
                        }
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 非 OLE/损坏文件抛运行时异常，统一包装为 IOException
            throw new IOException("PowerPoint(.ppt) 解析失败（可能不是有效的二进制 PPT 文档）: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "POI PowerPoint(.ppt) 解析器";
    }
}
