package com.omniforge.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImWhitelistTest {

    @Test
    void 默认关闭时放行全部() {
        ImWhitelist whitelist = new ImWhitelist(false, List.of());
        assertTrue(whitelist.allows("anyone"));
    }

    @Test
    void 开启但未配置则拒绝全部() {
        ImWhitelist whitelist = new ImWhitelist(true, List.of());
        assertFalse(whitelist.allows("anyone"), "开启白名单后未配置应拒绝（安全底线）");
    }

    @Test
    void 精确匹配() {
        ImWhitelist whitelist = new ImWhitelist(true, List.of("user-001"));
        assertTrue(whitelist.allows("user-001"));
        assertFalse(whitelist.allows("user-002"));
    }

    @Test
    void 前缀通配与全通配() {
        ImWhitelist whitelist = new ImWhitelist(true, List.of("admin_*"));
        assertTrue(whitelist.allows("admin_zhang"));
        assertFalse(whitelist.allows("member_li"));

        ImWhitelist all = new ImWhitelist(true, List.of("*"));
        assertTrue(all.allows("anything"));
    }
}
