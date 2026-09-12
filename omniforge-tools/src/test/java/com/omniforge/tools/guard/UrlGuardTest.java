package com.omniforge.tools.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlGuardTest {

    @Test
    void http与https放行() {
        assertEquals("searxng.example.com", UrlGuard.requireSafeHttpUrl("https://searxng.example.com").getHost());
        assertEquals("localhost", UrlGuard.requireSafeHttpUrl("http://localhost:8080").getHost());
    }

    @Test
    void 伪协议被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl("ftp://host/file"));
    }

    @Test
    void 非法URL被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl("not a url"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl(""));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.requireSafeHttpUrl("https://"));
    }

    @Test
    void 携带用户信息的URL被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuard.requireSafeHttpUrl("https://user:pass@host.com"));
    }
}
