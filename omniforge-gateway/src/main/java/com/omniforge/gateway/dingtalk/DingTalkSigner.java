package com.omniforge.gateway.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * 钉钉签名工具（纯 JDK 实现，零 SDK 依赖，Step 1 预检决策）：
 * 算法 = Base64(HmacSHA256(timestamp + "\n" + secret))，timestamp 为毫秒时间戳字符串。
 * 校验使用常量时间比较防时序攻击。
 */
public final class DingTalkSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private DingTalkSigner() {
    }

    /** 生成签名（用于回复请求的加签） */
    public static String sign(long timestamp, String secret) {
        Objects.requireNonNull(secret, "secret");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉签名计算失败", e);
        }
    }

    /** 校验回调签名（常量时间比较） */
    public static boolean verify(long timestamp, String sign, String secret) {
        if (sign == null || sign.isBlank()) {
            return false;
        }
        String expected = sign(timestamp, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }
}
