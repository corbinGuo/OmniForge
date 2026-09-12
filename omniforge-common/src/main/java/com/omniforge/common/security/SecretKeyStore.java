package com.omniforge.common.security;

import com.omniforge.common.exception.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Objects;

/**
 * 本地密钥库：管理主密钥并加密/解密机密文件。
 *
 * <p>布局：目录下 {@code .master.key} 存随机生成的主密钥（首次使用自动创建）；
 * 机密以 "IV+密文" 的 Base64 形式写入指定文件（如 {@code ~/.omniforge/keys/openai.key}）。</p>
 *
 * <p>注意：主密钥为纯文件保护，安全强度依赖用户目录隔离；
 * Windows ACL 收紧与 DPAPI 加固列入 Phase 4。</p>
 */
public final class SecretKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SecretKeyStore.class);
    private static final String MASTER_KEY_FILE = ".master.key";

    private final Path directory;
    private final SecretKey masterKey;

    /**
     * 打开（或初始化）密钥库。
     *
     * @param directory 密钥库目录（不存在时自动创建；首次使用会生成 .master.key）
     */
    public SecretKeyStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.masterKey = loadOrCreateMasterKey();
    }

    /** 加密机密并写入文件（覆盖写；自动创建父目录） */
    public void writeSecret(Path file, String secret) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(secret, "secret");
        try {
            byte[] ciphertext = CryptoUtil.encrypt(masterKey, secret.getBytes(StandardCharsets.UTF_8));
            Path target = file.toAbsolutePath();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, Base64.getEncoder().encode(ciphertext),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new CryptoException("机密写入失败: " + file, e);
        }
    }

    /**
     * 读取并解密机密文件。
     *
     * @throws CryptoException 文件缺失、内容非法、被篡改或主密钥不匹配时抛出
     */
    public String readSecret(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            byte[] encoded = Files.readAllBytes(file);
            byte[] decrypted = CryptoUtil.decrypt(masterKey, Base64.getDecoder().decode(encoded));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            throw new CryptoException("机密读取失败: " + file, e);
        }
    }

    /** 密钥库目录 */
    public Path directory() {
        return directory;
    }

    private SecretKey loadOrCreateMasterKey() {
        Path masterFile = directory.resolve(MASTER_KEY_FILE);
        try {
            if (Files.exists(masterFile)) {
                return new SecretKeySpec(Files.readAllBytes(masterFile), "AES");
            }
            SecretKey key = CryptoUtil.generateKey();
            Files.createDirectories(directory);
            Files.write(masterFile, key.getEncoded(), StandardOpenOption.CREATE_NEW);
            log.warn("已生成新的主密钥文件：{}（请勿删除，否则已加密机密将无法解密）",
                    masterFile.toAbsolutePath());
            return key;
        } catch (IOException e) {
            throw new CryptoException("主密钥文件读写失败: " + masterFile, e);
        }
    }
}
