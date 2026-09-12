package com.omniforge.common.security;

import com.omniforge.common.exception.CryptoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretKeyStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 写入后可读取且文件不存明文() throws Exception {
        SecretKeyStore store = new SecretKeyStore(tempDir.resolve("keys"));
        Path file = tempDir.resolve("keys").resolve("openai.key");
        store.writeSecret(file, "sk-plain-abcdef");

        assertEquals("sk-plain-abcdef", store.readSecret(file));
        assertFalse(Files.readString(file).contains("sk-plain-abcdef"), "机密文件不应包含明文");
        assertTrue(Files.exists(tempDir.resolve("keys/.master.key")), "首次使用应生成主密钥");
    }

    @Test
    void 主密钥持久化后重新打开仍可解密() {
        Path dir = tempDir.resolve("keys");
        new SecretKeyStore(dir).writeSecret(dir.resolve("a.key"), "value-a");

        SecretKeyStore reopened = new SecretKeyStore(dir);
        assertEquals("value-a", reopened.readSecret(dir.resolve("a.key")));
    }

    @Test
    void 文件被篡改后读取失败() throws Exception {
        Path dir = tempDir.resolve("keys");
        SecretKeyStore store = new SecretKeyStore(dir);
        Path file = dir.resolve("a.key");
        store.writeSecret(file, "value-a");

        String encoded = Files.readString(file);
        char original = encoded.charAt(encoded.length() / 2);
        char replaced = original == 'A' ? 'B' : 'A';
        Files.writeString(file, encoded.substring(0, encoded.length() / 2)
                + replaced + encoded.substring(encoded.length() / 2 + 1));

        assertThrows(CryptoException.class, () -> store.readSecret(file));
    }

    @Test
    void 文件缺失时读取失败() {
        SecretKeyStore store = new SecretKeyStore(tempDir.resolve("keys"));
        assertThrows(CryptoException.class,
                () -> store.readSecret(tempDir.resolve("keys").resolve("missing.key")));
    }
}
