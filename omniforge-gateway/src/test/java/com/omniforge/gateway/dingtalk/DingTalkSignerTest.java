package com.omniforge.gateway.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkSignerTest {

    @Test
    void 签名确定性与校验() {
        String sign1 = DingTalkSigner.sign(1720000000000L, "SECsecret");
        String sign2 = DingTalkSigner.sign(1720000000000L, "SECsecret");
        assertEquals(sign1, sign2, "同一时间戳与密钥的签名应一致");
        assertTrue(DingTalkSigner.verify(1720000000000L, sign1, "SECsecret"));
        assertNotEquals(sign1, DingTalkSigner.sign(1720000000001L, "SECsecret"),
                "时间戳不同签名应不同");
    }

    @Test
    void 篡改签名被拒绝() {
        String sign = DingTalkSigner.sign(1720000000000L, "SECsecret");
        assertFalse(DingTalkSigner.verify(1720000000000L, sign + "x", "SECsecret"));
        assertFalse(DingTalkSigner.verify(1720000000000L, null, "SECsecret"));
        assertFalse(DingTalkSigner.verify(1720000000000L, sign, "OTHERsecret"), "密钥不同应拒绝");
    }

    private static void assertEquals(String expected, String actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
