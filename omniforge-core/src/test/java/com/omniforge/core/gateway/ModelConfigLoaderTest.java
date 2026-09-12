package com.omniforge.core.gateway;

import com.omniforge.common.exception.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final ModelConfigLoader loader = new ModelConfigLoader();

    @Test
    void 加载合法配置() throws Exception {
        Path file = tempDir.resolve("models.yml");
        Files.writeString(file, """
                default-model: qwen-plus
                providers:
                  - name: dashscope
                    type: dashscope
                    api-key: ${DASHSCOPE_API_KEY}
                models:
                  - alias: qwen-plus
                    provider: dashscope
                    model-id: qwen-plus
                    input-price-per-1m: 0.8
                    output-price-per-1m: 2.0
                """);

        ModelGatewayConfig config = loader.load(file);

        assertEquals("qwen-plus", config.getDefaultModel());
        assertEquals(1, config.getProviders().size());
        assertEquals(1, config.getModels().size());
        assertEquals("dashscope", config.findModel("qwen-plus").getProvider());
        assertEquals(0.8, config.findModel("qwen-plus").getInputPricePer1m());
        assertEquals(120, config.findModel("qwen-plus").getTimeoutSeconds(), "timeout-seconds 缺省应为 120");
    }

    @Test
    void 文件缺失时生成默认模板() {
        Path file = tempDir.resolve("models.yml");
        ModelGatewayConfig config = loader.loadOrCreate(file);

        assertTrue(Files.exists(file), "首次加载应生成默认模板");
        assertNotNull(config.getDefaultModel());
        assertTrue(config.getModels().size() >= 1, "默认模板应包含至少一个模型");
    }

    @Test
    void 引用不存在的提供商时校验失败() throws Exception {
        Path file = tempDir.resolve("models.yml");
        Files.writeString(file, """
                models:
                  - alias: m
                    provider: ghost
                    model-id: m
                """);

        assertThrows(ConfigurationException.class, () -> loader.load(file));
    }

    @Test
    void 文件缺失时load直接抛配置异常() {
        assertThrows(ConfigurationException.class, () -> loader.load(tempDir.resolve("nope.yml")));
    }
}
