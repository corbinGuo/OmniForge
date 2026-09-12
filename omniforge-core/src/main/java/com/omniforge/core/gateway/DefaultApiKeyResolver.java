package com.omniforge.core.gateway;

import com.omniforge.common.exception.ConfigurationException;
import com.omniforge.common.security.SecretKeyStore;
import com.omniforge.common.security.SensitiveMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 API Key 解析器。
 *
 * <ol>
 *   <li>{@code ${ENV_VAR}} —— 从环境变量读取（推荐）；</li>
 *   <li>{@code keystore:<id>} —— 从加密存储读取 keysDirectory/&lt;id&gt;.key；</li>
 *   <li>明文 —— 仅限个人测试，加载时输出一次告警（日志只记录掩码后的值）。</li>
 * </ol>
 */
public class DefaultApiKeyResolver implements ApiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultApiKeyResolver.class);
    private static final String KEYSTORE_PREFIX = "keystore:";

    private final Path keysDirectory;
    private final SecretKeyStore keyStore;
    private final Set<String> warnedPlaintextKeys = ConcurrentHashMap.newKeySet();

    /** @param keysDirectory 密钥库目录（~/.omniforge/keys），首次使用自动初始化主密钥 */
    public DefaultApiKeyResolver(Path keysDirectory) {
        this.keysDirectory = Objects.requireNonNull(keysDirectory, "keysDirectory");
        this.keyStore = new SecretKeyStore(keysDirectory);
    }

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ConfigurationException("API Key 引用为空");
        }
        if (reference.startsWith("${") && reference.endsWith("}")) {
            String envName = reference.substring(2, reference.length() - 1);
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new ConfigurationException("环境变量未设置: " + envName);
            }
            return value;
        }
        if (reference.startsWith(KEYSTORE_PREFIX)) {
            String id = reference.substring(KEYSTORE_PREFIX.length());
            return keyStore.readSecret(keysDirectory.resolve(id + ".key"));
        }
        if (warnedPlaintextKeys.add(reference)) {
            log.warn("API Key 使用明文配置（不推荐，仅限个人测试）：{}", SensitiveMasker.maskApiKey(reference));
        }
        return reference;
    }
}
