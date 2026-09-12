package com.omniforge.knowledge.doc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * 纯文本解析器（知识库文件格式扩展 2026-09-04）：txt / md / csv / json / yml 等
 * 数据/配置/源码文本类文件。
 *
 * <p>解码优先 UTF-8 严格，失败自动降级 GB18030（中文 GBK/GB18030 导出文本）；
 * Markdown 等暂按纯文本处理，语法符号保留。html/htm/rtf 与 Office 类归其他解析器。</p>
 */
public final class PlainTextParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yml", "yaml", "properties",
            "ini", "conf", "cfg", "toml", "sql", "css", "js", "ts", "bat", "cmd", "ps1", "sh",
            "py", "java", "c", "cpp", "cc", "h", "hpp");

    @Override
    public boolean supports(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    @Override
    public String parse(Path file) throws IOException {
        return TextDecoding.read(file);
    }

    @Override
    public String name() {
        return "纯文本解析器";
    }
}
