package com.omniforge.gateway.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 飞书事件回调签名校验（事件 v1 未加密场景）：
 * X-Lark-Signature = hex(SHA256(timestamp + nonce + encryptKey + body))。
 * 常量时间比较防时序攻击。
 */
public final class FeishuSigner {

    private FeishuSigner() {
    }

    public static boolean verify(String timestamp, String nonce, String encryptKey, String body,
                                 String expectedSignature) {
        Objects.requireNonNull(encryptKey, "encryptKey");
        if (expectedSignature == null || expectedSignature.isBlank()) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((timestamp + nonce + encryptKey + body)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
