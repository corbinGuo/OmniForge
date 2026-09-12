package com.omniforge.core.agent.plugin;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.core.agent.DefaultToolRegistry;
import com.omniforge.core.agent.plugin.testplugin.TestGoodbyePlugin;
import com.omniforge.core.agent.plugin.testplugin.TestHelloPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件热加载测试：运行时把测试源码内的插件类打包成 jar，
 * 验证启动扫描加载 / 目录变化热加载 / 删除卸载 / 修改重载 / 同名冲突 / 损坏 jar 跳过。
 */
class PluginManagerTest {

    @TempDir
    Path pluginsDir;

    private DefaultToolRegistry registry;
    private PluginManager manager;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
        PluginProperties properties = new PluginProperties();
        properties.setPluginsDir(pluginsDir);
        properties.setWatchDebounceMillis(50);
        manager = new PluginManager(properties, registry);
    }

    @AfterEach
    void tearDown() {
        if (manager.isRunning()) {
            manager.stop();
        }
    }

    @Test
    void 启动扫描加载插件jar并注册工具() throws Exception {
        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));
        manager.start();

        assertThat(awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent()))
                .as("启动扫描应注册插件工具").isTrue();
        assertThat(manager.loadedPluginNames()).contains("test-hello");
        assertThat(pluginsDir.resolve(PluginManager.STAGING_DIR).resolve("hello.jar")).exists();
    }

    @Test
    void 删除jar自动卸载工具并清理staging副本() throws Exception {
        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));
        manager.start();
        awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent());

        Files.delete(pluginsDir.resolve("hello.jar"));

        assertThat(awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isEmpty()))
                .as("jar 删除后应自动卸载工具").isTrue();
        assertThat(awaitTrue(() -> !Files.exists(
                pluginsDir.resolve(PluginManager.STAGING_DIR).resolve("hello.jar"))))
                .as("staging 副本应被清理").isTrue();
    }

    @Test
    void 启动后新增jar被热加载() throws Exception {
        manager.start();
        awaitTrue(() -> Files.exists(pluginsDir)); // watcher 注册就绪

        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));

        assertThat(awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent()))
                .as("目录变化应触发热加载").isTrue();
    }

    @Test
    void jar内容变化自动重载并替换工具集() throws Exception {
        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));
        manager.start();
        awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent());

        // v2：同路径 jar 换成 hello+goodbye 两个插件
        Path v2 = buildPluginJar("hello-v2.jar", List.of(
                TestHelloPlugin.class.getName(), TestGoodbyePlugin.class.getName()));
        Files.move(v2, pluginsDir.resolve("hello.jar"), StandardCopyOption.REPLACE_EXISTING);

        assertThat(awaitTrue(() -> registry.find(TestGoodbyePlugin.TOOL_NAME).isPresent()))
                .as("重载后应注册新插件工具").isTrue();
        assertThat(registry.find(TestHelloPlugin.TOOL_NAME)).isPresent();
        assertThat(registry.all()).hasSize(2);
        assertThat(manager.loadedPluginNames())
                .containsExactlyInAnyOrder("test-hello", "test-goodbye");
    }

    @Test
    void 同名工具冲突保留先注册者且不影响插件加载() throws Exception {
        registry.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(TestHelloPlugin.TOOL_NAME, "预先存在的同名工具", Map.of(), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("builtin", 0);
            }
        });
        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));
        manager.start();

        awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent());
        assertThat(manager.loadedPluginNames()).contains("test-hello");
        assertThat(registry.find(TestHelloPlugin.TOOL_NAME).orElseThrow().spec().description())
                .isEqualTo("预先存在的同名工具"); // 先注册者胜，插件工具被忽略
    }

    @Test
    void 损坏jar加载失败但管理器不受影响() throws Exception {
        Files.write(pluginsDir.resolve("bad.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(pluginsDir.resolve(PluginManager.STAGING_DIR));
        manager.start();

        awaitTrue(() -> true);
        assertThat(manager.isRunning()).isTrue();
        assertThat(registry.all()).isEmpty();
        assertThat(manager.loadedPluginNames()).isEmpty();
    }

    @Test
    void 服务文件声明缺失类时回滚已注册工具且不崩溃() throws Exception {
        // services 声明两个实现：hello 有效、ghost 类文件缺失——
        // 迭代到 ghost 抛 ServiceConfigurationError（Error），
        // 必须兜住并回滚已注册的 hello 工具（真机曾致应用启动失败）
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(
                pluginsDir.resolve("mixed.jar")))) {
            out.putNextEntry(new JarEntry(
                    "META-INF/services/com.omniforge.common.spi.OmniForgePlugin"));
            out.write((TestHelloPlugin.class.getName() + "\n"
                    + "com.omniforge.core.agent.plugin.testplugin.GhostPlugin")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(
                    TestHelloPlugin.class.getName().replace('.', '/') + ".class"));
            Files.copy(Paths.get(TestHelloPlugin.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI())
                    .resolve(TestHelloPlugin.class.getName().replace('.', '/') + ".class"), out);
            out.closeEntry();
        }
        manager.start();

        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.loadedPluginNames()).isEmpty();
        assertThat(awaitTrue(() -> true)).isTrue();
        assertThat(registry.all()).isEmpty(); // 已注册的 hello 被回滚
    }

    @Test
    void stop时卸载全部插件() throws Exception {
        buildPluginJar("hello.jar", List.of(TestHelloPlugin.class.getName()));
        manager.start();
        awaitTrue(() -> registry.find(TestHelloPlugin.TOOL_NAME).isPresent());

        manager.stop();

        assertThat(registry.find(TestHelloPlugin.TOOL_NAME)).isEmpty();
        assertThat(manager.loadedPluginNames()).isEmpty();
    }

    /** 在指定目录构建插件 jar：内含实现类 + META-INF/services 声明 */
    private Path buildPluginJar(String jarName, List<String> implClasses)
            throws IOException, URISyntaxException {
        Path classesDir = Paths.get(TestHelloPlugin.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path jar = pluginsDir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(
                    "META-INF/services/com.omniforge.common.spi.OmniForgePlugin"));
            out.write(String.join("\n", implClasses).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (String impl : implClasses) {
                String classPath = impl.replace('.', '/') + ".class";
                Path classFile = classesDir.resolve(classPath);
                out.putNextEntry(new JarEntry(classPath));
                Files.copy(classFile, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    /** 轮询等待条件成立（100ms 间隔，默认 5 秒超时） */
    private static boolean awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
