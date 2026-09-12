package com.omniforge.knowledge.doc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本解码工具（doc 包内部）：纯文本类文件优先按 UTF-8 严格解码，
 * 失败自动降级 GB18030（兼容中文 GBK/GB18030 编码的 CSV/Excel 导出文本）。
 */
final class TextDecoding {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private TextDecoding() {
    }

    static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /** UTF-8 严格 → 失败则 GB18030（替换非法字节，不抛） */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // GB18030 为超集（含 GBK），new String 对非法字节按替换处理，不抛
            return new String(bytes, GB18030);
        }
    }
}
