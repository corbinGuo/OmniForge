package com.omniforge.core.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelGatewayAutoConfiguration.class));

    @Test
    void 上下文装配出完整网关Bean() throws Exception {
        Path configFile = tempDir.resolve("models.yml");
        Files.writeString(configFile, """
                default-model: test-model
                providers:
                  - name: mock
                    type: openai-compatible
                    base-url: http://localhost:9999/v1
                    api-key: sk-test-key-1234567890
                models:
                  - alias: test-model
                    provider: mock
                    model-id: test-model-1
                """);

        contextRunner
                .withPropertyValues(
                        "omniforge.gateway.config-file=" + configFile.toAbsolutePath(),
                        "omniforge.gateway.watch-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context).hasSingleBean(DefaultModelGateway.class);
                    assertThat(context).hasSingleBean(ChatModelProvider.class);
                    assertThat(context).hasSingleBean(ApiKeyResolver.class);
                    assertThat(context).hasSingleBean(ModelRouter.class);
                    assertThat(context).doesNotHaveBean(ModelConfigWatcher.class);

                    ModelGateway gateway = context.getBean(ModelGateway.class);
                    assertThat(gateway.availableModels()).extracting(ModelInfo::alias).contains("test-model");
                });
    }
}
