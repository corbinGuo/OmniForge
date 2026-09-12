package com.omniforge.common.security;

import com.omniforge.common.exception.CryptoException;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoUtilTest {

    private static final byte[] PLAINTEXT = "sk-test-123456".getBytes(StandardCharsets.UTF_8);

    @Test
    void 加密解密往返一致() {
        SecretKey key = CryptoUtil.generateKey();
        byte[] cipher = CryptoUtil.encrypt(key, PLAINTEXT);
        assertFalse(new String(cipher, StandardCharsets.UTF_8).contains("sk-test"), "密文不应包含明文");
        assertArrayEquals(PLAINTEXT, CryptoUtil.decrypt(key, cipher));
    }

    @Test
    void 空明文往返一致() {
        SecretKey key = CryptoUtil.generateKey();
        byte[] cipher = CryptoUtil.encrypt(key, new byte[0]);
        assertArrayEquals(new byte[0], CryptoUtil.decrypt(key, cipher));
    }

    @Test
    void 密文被篡改后解密失败() {
        SecretKey key = CryptoUtil.generateKey();
        byte[] cipher = CryptoUtil.encrypt(key, PLAINTEXT);
        cipher[cipher.length - 1] ^= 0x01; // 翻转认证标签最后一位
        assertThrows(CryptoException.class, () -> CryptoUtil.decrypt(key, cipher));
    }

    @Test
    void 密钥不匹配解密失败() {
        byte[] cipher = CryptoUtil.encrypt(CryptoUtil.generateKey(), PLAINTEXT);
        assertThrows(CryptoException.class, () -> CryptoUtil.decrypt(CryptoUtil.generateKey(), cipher));
    }

    @Test
    void 非法密文解密失败() {
        SecretKey key = CryptoUtil.generateKey();
        assertThrows(CryptoException.class, () -> CryptoUtil.decrypt(key, new byte[]{1, 2, 3}));
    }

    @Test
    void 同口令同盐派生密钥一致且可用() {
        byte[] salt = CryptoUtil.generateSalt();
        SecretKey k1 = CryptoUtil.deriveKey("pass".toCharArray(), salt);
        SecretKey k2 = CryptoUtil.deriveKey("pass".toCharArray(), salt);
        assertArrayEquals(k1.getEncoded(), k2.getEncoded());

        byte[] cipher = CryptoUtil.encrypt(k1, PLAINTEXT);
        assertArrayEquals(PLAINTEXT, CryptoUtil.decrypt(k2, cipher));
    }

    @Test
    void 随机盐保证派生密钥不同() {
        char[] pass = "pass".toCharArray();
        assertFalse(java.util.Arrays.equals(
                CryptoUtil.deriveKey(pass, CryptoUtil.generateSalt()).getEncoded(),
                CryptoUtil.deriveKey(pass, CryptoUtil.generateSalt()).getEncoded()));
    }
}
