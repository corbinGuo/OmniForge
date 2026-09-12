package com.omniforge.gateway.qq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ed25519 验签测试：以官方文档回调验证示例为黄金向量
 * （secret DG5g3B4j9X2KOErG / event_ts 1725442341 / plain_token Arq0D5A61EgUu4OxUvOp
 * → 87befc99...），证明 PKCS#8 seed 派生与签名算法与官方 Go 实现完全一致。
 */
class QqSignatureVerifierTest {

    private static final String SECRET = "DG5g3B4j9X2KOErG";
    private static final String EVENT_TS = "1725442341";
    private static final String PLAIN_TOKEN = "Arq0D5A61EgUu4OxUvOp";
    private static final String OFFICIAL_SIGNATURE =
            "87befc99c42c651b3aac0278e71ada338433ae26fcb24307bdc5ad38c1adc2d01bcfcadc0842edac85e85205028a1132afe09280305f13aa6909ffc2d652c706";

    @Test
    void 官方文档向量签名一致() {
        assertThat(QqSignatureVerifier.signCallback(SECRET, EVENT_TS, PLAIN_TOKEN))
                .isEqualTo(OFFICIAL_SIGNATURE);
    }

    @Test
    void 回调签名校验正确与篡改拒绝() {
        assertThat(QqSignatureVerifier.verifyCallback(SECRET, EVENT_TS, PLAIN_TOKEN, OFFICIAL_SIGNATURE))
                .isTrue();
        // 篡改 plain_token 或签名即失败
        assertThat(QqSignatureVerifier.verifyCallback(SECRET, EVENT_TS, "tampered", OFFICIAL_SIGNATURE))
                .isFalse();
        assertThat(QqSignatureVerifier.verifyCallback(SECRET, EVENT_TS, PLAIN_TOKEN,
                OFFICIAL_SIGNATURE.replace('7', '8')))
                .isFalse();
    }

    @Test
    void 短密钥按官方规则重复扩展() {
        // "abc" → 字节数 3 < 32 → 翻倍直至 ≥32，取前 32 字节
        byte[] seed = QqSignatureVerifier.seedOf("abc");
        assertThat(seed).hasSize(32);
        byte[] repeated = "abc".repeat(11).getBytes(java.nio.charset.StandardCharsets.UTF_8); // 33 字节
        assertThat(seed).isEqualTo(java.util.Arrays.copyOf(repeated, 32));
    }

    @Test
    void 事件推送验签往返() {
        String body = "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"ROBOT1.0_x\",\"event_ts\":\"1725442341\"}}";
        // 签名算法 = seed(secret) 对 event_ts + raw_body 的确定性签名；此处用回调同款消息验证往返
        String sig = QqSignatureVerifier.signCallback(SECRET, EVENT_TS, body);
        assertThat(QqSignatureVerifier.verifyEvent(SECRET, EVENT_TS, body, sig)).isTrue();
        assertThat(QqSignatureVerifier.verifyEvent(SECRET, EVENT_TS, body + "x", sig)).isFalse();
    }
}
