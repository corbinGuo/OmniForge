package com.omniforge.core.gateway;

import com.omniforge.common.exception.ConfigurationException;
import com.omniforge.common.security.SecretKeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultApiKeyResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void 明文引用原样返回() {
        DefaultApiKeyResolver resolver = new DefaultApiKeyResolver(tempDir.resolve("keys"));
        assertEquals("sk-plain", resolver.resolve("sk-plain"));
    }

    @Test
    void 未设置环境变量时报错() {
        DefaultApiKeyResolver resolver = new DefaultApiKeyResolver(tempDir.resolve("keys"));
        assertThrows(ConfigurationException.class,
                () -> resolver.resolve("${NO_SUCH_ENV_VAR_OMNIFORGE_TEST}"));
    }

    @Test
    void keystore引用读取加密机密() {
        Path keysDir = tempDir.resolve("keys");
        new SecretKeyStore(keysDir).writeSecret(keysDir.resolve("openai.key"), "sk-secret-value");

        DefaultApiKeyResolver resolver = new DefaultApiKeyResolver(keysDir);
        assertEquals("sk-secret-value", resolver.resolve("keystore:openai"));
    }

    @Test
    void 空引用报错() {
        DefaultApiKeyResolver resolver = new DefaultApiKeyResolver(tempDir.resolve("keys"));
        assertThrows(ConfigurationException.class, () -> resolver.resolve(" "));
    }
}
