package com.omniforge.gateway.qq;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;

/**
 * QQ 官方机器人 Webhook Ed25519 签名（官方算法，seed=AppSecret）：
 * <ul>
 *   <li>回调地址验证（opcode 13）：{@code sign(seed, event_ts + plain_token)} hex 返回；</li>
 *   <li>事件推送验签：{@code sign(seed, event_ts + raw_body)} 与 X-Signature 比较。</li>
 * </ul>
 *
 * <p>seed 规则（官方示例代码）：AppSecret 长度不足 32 字节时重复自身直至足够，
 * 取前 32 字节作 Ed25519 seed。Ed25519 为确定性签名，故"验证"实现为
 * 重新计算签名后常量时间比较（JDK 内置 Ed25519 支持，零新依赖）。</p>
 */
public final class QqSignatureVerifier {

    private QqSignatureVerifier() {
    }

    /**
     * 计算回调验证签名（opcode 13 应答）。
     *
     * @param secret    AppSecret（seed）
     * @param eventTs   平台下发的 event_ts（秒级时间戳字符串）
     * @param plainToken 平台下发的 plain_token
     * @return 十六进制签名
     */
    public static String signCallback(String secret, String eventTs, String plainToken) {
        return sign(seedOf(secret), eventTs + plainToken);
    }

    /**
     * 校验回调验证请求签名。
     */
    public static boolean verifyCallback(String secret, String eventTs, String plainToken, String signature) {
        return constantTimeHexEquals(signCallback(secret, eventTs, plainToken), signature);
    }

    /**
     * 校验事件推送签名（X-Signature: ed25519=hex；msg = event_ts + 原始请求体）。
     *
     * @param secret    AppSecret（seed）
     * @param eventTs   事件体 d.event_ts（秒级时间戳字符串）
     * @param rawBody   完整原始请求体（JSON 原文，未经重排）
     * @param signature X-Signature 中 "ed25519=" 之后的部分
     */
    public static boolean verifyEvent(String secret, String eventTs, String rawBody, String signature) {
        return constantTimeHexEquals(sign(seedOf(secret), eventTs + rawBody), signature);
    }

    /** seed 规整：不足 32 字节重复自身，取前 32 字节（官方算法） */
    static byte[] seedOf(String secret) {
        String seed = secret == null ? "" : secret;
        while (seed.getBytes(StandardCharsets.UTF_8).length < 32) {
            seed = seed + seed;
        }
        byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[32];
        System.arraycopy(bytes, 0, result, 0, 32);
        return result;
    }

    private static String sign(byte[] seed, String message) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Encode(seed)));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 签名失败：" + e.getMessage(), e);
        }
    }

    /**
     * Ed25519 私钥 PKCS#8 编码（RFC 8410 §7，seed 版本）：
     * {@code 30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 || seed[32]}。
     */
    private static byte[] pkcs8Encode(byte[] seed) {
        byte[] result = new byte[48];
        int i = 0;
        for (byte b : new byte[]{
                0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20}) {
            result[i++] = b;
        }
        System.arraycopy(seed, 0, result, i, 32);
        return result;
    }

    private static boolean constantTimeHexEquals(String computedHex, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                computedHex.getBytes(StandardCharsets.US_ASCII),
                provided.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
