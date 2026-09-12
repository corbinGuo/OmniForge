package com.omniforge.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SensitiveMaskerTest {

    @Test
    void 掩码ApiKey保留首尾各四位() {
        String masked = SensitiveMasker.maskApiKey("sk-abcdefghijklmnop1234");
        assertEquals("sk-a****1234", masked);
        assertFalse(masked.contains("defghij"), "掩码结果不应包含中间片段");
    }

    @Test
    void 短ApiKey全掩() {
        assertEquals("*****", SensitiveMasker.maskApiKey("short"));
    }

    @Test
    void 掩码通用敏感串() {
        assertEquals("ab**ef", SensitiveMasker.mask("abcdef"), "保留首2尾2字符，中间全部掩码");
        assertEquals("***", SensitiveMasker.mask("abc"));
    }

    @Test
    void 空值安全() {
        assertNull(SensitiveMasker.maskApiKey(null));
        assertNull(SensitiveMasker.mask(null));
    }
}
