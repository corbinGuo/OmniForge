package com.omniforge.knowledge.doc;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Excel 解析器（.xlsx/.xls，Apache POI 5.3.0，知识库文件格式扩展 2026-09-04）。
 * 逐工作表逐行取单元格显示文本（DataFormatter 按显示格式），工作表间用「【表：名称】」标记分隔。
 * 空单元格跳过；行内列之间以制表符连接，保留列语义。
 */
public final class OfficeExcelParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public String parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(in)) {
            return extract(workbook);
        } catch (Exception e) {
            // POI 对损坏/非工作簿文件会抛运行时异常，统一包装为 IOException
            throw new IOException("Excel 解析失败（可能不是有效的工作簿）: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "POI Excel 解析器";
    }

    private static String extract(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        for (Sheet sheet : workbook) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【表：").append(sheet.getSheetName()).append('】');
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                StringBuilder line = new StringBuilder();
                boolean any = false;
                int first = row.getFirstCellNum();
                int last = row.getLastCellNum();
                for (int c = first >= 0 ? first : 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    String trimmed = value == null ? "" : value.strip();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (any) {
                        line.append('\t');
                    }
                    line.append(trimmed);
                    any = true;
                }
                if (any) {
                    sb.append('\n').append(line);
                }
            }
        }
        return sb.toString();
    }
}
