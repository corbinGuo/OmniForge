package com.omniforge.knowledge.doc;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PowerPoint（.pptx）解析器（Apache POI XMLSlideShow，知识库文件格式扩展 2026-09-04）。
 * 逐幻灯片取其文本框内容，幻灯片间以「【幻灯片 N】」标记分隔。
 */
public final class PptxParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".pptx");
    }

    @Override
    public String parse(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(in)) {
            int index = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("【幻灯片 ").append(++index).append('】');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append('\n').append(text);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "POI PowerPoint 解析器";
    }
}
