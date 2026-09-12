package com.omniforge.common.security;

import com.omniforge.common.exception.CryptoException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * JCE 加解密工具（AES-256-GCM）。
 *
 * <p>用于 API Key 等敏感信息的本地加密存储（需求 9.2 安全底线）。
 * 密文格式：{12字节 IV}{密文+128位认证标签}；解密时 GCM 认证失败
 * （密钥不匹配或密文被篡改）抛出 {@link CryptoException}，具备防篡改能力。</p>
 */
public final class CryptoUtil {

    /** GCM IV 长度（字节） */
    public static final int IV_LENGTH = 12;
    /** GCM 认证标签长度（位） */
    public static final int TAG_LENGTH_BITS = 128;
    /** PBKDF2 迭代次数（2026 年建议 ≥ 210k） */
    public static final int PBKDF2_ITERATIONS = 210_000;

    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /** 生成随机 AES-256 密钥 */
    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
            keyGenerator.init(256);
            return keyGenerator.generateKey();
        } catch (Exception e) {
            throw new CryptoException("无法生成 AES 密钥", e);
        }
    }

    /**
     * 从口令派生密钥（PBKDF2WithHmacSHA256，256 位）。
     * 相同的口令与盐派生结果一致；盐应每次随机生成。
     */
    public static SecretKey deriveKey(char[] passphrase, byte[] salt) {
        Objects.requireNonNull(passphrase, "passphrase");
        Objects.requireNonNull(salt, "salt");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), KEY_ALGORITHM);
        } catch (Exception e) {
            throw new CryptoException("密钥派生失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 生成随机盐（16 字节） */
    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    /** AES-GCM 加密，返回 IV+密文 的拼接字节数组 */
    public static byte[] encrypt(SecretKey key, byte[] plaintext) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plaintext, "plaintext");
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new CryptoException("加密失败", e);
        }
    }

    /**
     * AES-GCM 解密。
     *
     * @param encrypted 由 {@link #encrypt} 产出的 IV+密文 拼接字节数组
     * @throws CryptoException 密钥不匹配或密文被篡改（GCM 认证失败）时抛出
     */
    public static byte[] decrypt(SecretKey key, byte[] encrypted) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(encrypted, "encrypted");
        if (encrypted.length <= IV_LENGTH) {
            throw new CryptoException("密文格式非法：长度不足");
        }
        try {
            byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_LENGTH, encrypted.length);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("解密失败（密钥不匹配或密文被篡改）", e);
        }
    }
}
