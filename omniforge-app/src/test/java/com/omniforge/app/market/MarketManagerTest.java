package com.omniforge.app.market;

import com.omniforge.tools.mcp.McpSettings;
import com.omniforge.tools.mcp.McpSettingsHolder;
import com.omniforge.tools.mcp.McpSettingsStore;
import com.omniforge.ui.plugin.PluginBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketManagerTest {

    @TempDir
    Path temp;
    private Path pluginsDir;
    private Path marketDir;
    private Path skillsDir;
    private Path stateFile;
    private Path mcpFile;
    private McpSettingsHolder mcpHolder;
    private MarketManager manager;

    @BeforeEach
    void setUp() throws IOException {
        pluginsDir = temp.resolve("plugins");
        marketDir = temp.resolve("market");
        skillsDir = temp.resolve("skills");
        stateFile = marketDir.resolve("state.json");
        mcpFile = temp.resolve("mcp.yml");
        mcpHolder = new McpSettingsHolder(McpSettings.empty());
        manager = new MarketManager(pluginsDir, marketDir, skillsDir, stateFile, mcpFile,
                null, new McpSettingsStore(), mcpHolder);
    }

    @Test
    void 市场扫描列出未安装包() throws Exception {
        writeJarPackage("hello", "1.0.0", "A".getBytes(StandardCharsets.UTF_8));
        PluginBridge.Overview overview = manager.overview();
        assertThat(overview.available()).hasSize(1);
        PluginBridge.AvailablePlugin pkg = overview.available().get(0);
        assertThat(pkg.id()).isEqualTo("hello");
        assertThat(pkg.format()).isEqualTo("jar");
        assertThat(pkg.state()).isEqualTo(PluginBridge.STATE_NOT_INSTALLED);
    }

    @Test
    void jar安装复制到插件目录并记录状态() throws Exception {
        byte[] bytes = "hello-plugin-v1".getBytes(StandardCharsets.UTF_8);
        writeJarPackage("hello", "1.0.0", bytes);
        manager.install("hello");
        assertThat(Files.readAllBytes(pluginsDir.resolve("hello.jar"))).isEqualTo(bytes);
        MarketState state = new MarketStateStore().load(stateFile);
        MarketState.Item item = state.find("hello");
        assertThat(item).isNotNull();
        assertThat(item.version()).isEqualTo("1.0.0");
        assertThat(manager.overview().available().get(0).state())
                .isEqualTo(PluginBridge.STATE_INSTALLED);
    }

    @Test
    void jar升级归档旧版且回滚恢复() throws Exception {
        byte[] v1 = "version-one".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "version-two!!".getBytes(StandardCharsets.UTF_8);
        writeJarPackage("hello", "1.0.0", v1);
        manager.install("hello");

        writeJarPackage("hello", "2.0.0", v2);
        manager.install("hello"); // 升级
        assertThat(Files.readAllBytes(pluginsDir.resolve("hello.jar"))).isEqualTo(v2);
        assertThat(Files.readAllBytes(marketDir.resolve("archive/hello/previous.jar")))
                .isEqualTo(v1);
        assertThat(new MarketStateStore().load(stateFile).find("hello").version()).isEqualTo("2.0.0");

        manager.rollback("hello"); // 回滚到上一版
        assertThat(Files.readAllBytes(pluginsDir.resolve("hello.jar"))).isEqualTo(v1);
        assertThat(new MarketStateStore().load(stateFile).find("hello").version()).isEqualTo("1.0.0");
    }

    @Test
    void 强传染许可禁止安装() throws Exception {
        writePackage("bad", """
                {"format":"jar","id":"bad","name":"Bad","version":"1.0.0",
                 "license":"AGPL-3.0","payload":"bad.jar"}
                """);
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(marketDir.resolve("bad"));
        Files.write(marketDir.resolve("bad/bad.jar"), bytes);
        assertThatThrownBy(() -> manager.install("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGPL");
        assertThat(Files.exists(pluginsDir.resolve("bad.jar"))).isFalse();
    }

    @Test
    void 技能安装默认启用并注入系统提示() throws Exception {
        writeSkillPackage("sonnet", "Sonnet 技能", "当用户要求写诗时使用十四行体。");
        manager.install("sonnet");
        assertThat(Files.isRegularFile(skillsDir.resolve("sonnet/SKILL.md"))).isTrue();
        // 技能内容并入系统提示（安装即启用）
        String text = manager.extraSystemText();
        assertThat(text).isNotNull().contains("十四行体").startsWith("【技能：Sonnet 技能】");
        // 已装技能列表：描述取自 frontmatter description
        assertThat(manager.overview().skills()).extracting(PluginBridge.InstalledSkill::name)
                .contains("sonnet");

        manager.setEnabled(PluginBridge.FORMAT_SKILL, "sonnet", false);
        assertThat(manager.extraSystemText()).isNull();
        manager.setEnabled(PluginBridge.FORMAT_SKILL, "sonnet", true);
        assertThat(manager.extraSystemText()).contains("十四行体");
    }

    @Test
    void 技能指令总量超限整体截断() throws Exception {
        String body = "大".repeat(9_000);
        writeSkillPackage("verbose", "Verbose", body);
        manager.install("verbose");
        String text = manager.extraSystemText();
        assertThat(text).hasSize(8000); // 从头取 8000 字
    }

    @Test
    void mcp安装并入mcpyml且可启停卸载() throws Exception {
        writePackage("fs", """
                {"format":"mcp","id":"fs","name":"Filesystem","version":"1.0.0",
                 "payload":"fs.json"}
                """);
        Files.createDirectories(marketDir.resolve("fs"));
        Files.writeString(marketDir.resolve("fs/fs.json"), """
                {"transport":"stdio","command":"npx",
                 "args":["-y","@modelcontextprotocol/server-filesystem","C:/data"]}
                """);
        manager.install("fs");
        McpSettings settings = new McpSettingsStore().load(mcpFile);
        assertThat(settings.servers()).hasSize(1);
        assertThat(settings.servers().get(0).name()).isEqualTo("fs");
        assertThat(settings.servers().get(0).enabled()).isTrue();

        manager.setEnabled(PluginBridge.FORMAT_MCP, "fs", false);
        assertThat(mcpHolder.current().servers().get(0).enabled()).isFalse();

        manager.uninstall("fs");
        assertThat(new McpSettingsStore().load(mcpFile).servers()).isEmpty();
    }

    @Test
    void zip插件包导入后出现在市场() throws Exception {
        Path pkg = temp.resolve("hello-pkg");
        writeJarPackageInto("hello", "1.0.0", pkg, "x".getBytes(StandardCharsets.UTF_8));
        Path zip = temp.resolve("hello.zip");
        zipDirectory(pkg, zip);
        manager.importPackage(zip);
        assertThat(manager.overview().available())
                .extracting(PluginBridge.AvailablePlugin::id).contains("hello");
    }

    // ---------- helpers ----------

    private void writeJarPackage(String id, String version, byte[] jarBytes) throws IOException {
        writePackage(id, """
                {"format":"jar","id":"%s","name":"%s","version":"%s",
                 "description":"test","author":"test","license":"Apache-2.0",
                 "payload":"payload/%s.jar"}
                """.formatted(id, id, version, id));
        Path dir = marketDir.resolve(id);
        Files.createDirectories(dir.resolve("payload"));
        Files.write(dir.resolve("payload").resolve(id + ".jar"), jarBytes);
    }

    private void writeSkillPackage(String id, String name, String body) throws IOException {
        writePackage(id, """
                {"format":"skill","id":"%s","name":"%s","version":"1.0.0",
                 "description":"skill","payload":"skill"}
                """.formatted(id, name));
        Path dir = marketDir.resolve(id);
        Files.createDirectories(dir.resolve("skill"));
        Files.writeString(dir.resolve("skill/SKILL.md"),
                "---\nname: " + name + "\ndescription: " + name + " 技能描述\n---\n" + body);
    }

    private void writePackage(String id, String manifestJson) throws IOException {
        Path dir = marketDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MarketPackage.MANIFEST_FILE), manifestJson);
    }

    private void writeJarPackageInto(String id, String version, Path pkgDir, byte[] jarBytes)
            throws IOException {
        Path dir = pkgDir;
        Files.createDirectories(dir.resolve("payload"));
        Files.writeString(dir.resolve(MarketPackage.MANIFEST_FILE), """
                {"format":"jar","id":"%s","name":"%s","version":"%s","payload":"payload/%s.jar"}
                """.formatted(id, id, version, id));
        Files.write(dir.resolve("payload").resolve(id + ".jar"), jarBytes);
    }

    private static void zipDirectory(Path source, Path zip) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip,
                StandardOpenOption.CREATE))) {
            try (var stream = Files.walk(source)) {
                for (Path from : stream.sorted().toList()) {
                    String entry = source.relativize(from).toString();
                    if (entry.isEmpty()) {
                        continue; // 顶层目录本身不入 zip（解压后 staging 顶层即包内容）
                    }
                    if (Files.isDirectory(from)) {
                        out.putNextEntry(new ZipEntry(entry + "/"));
                        out.closeEntry();
                    } else {
                        out.putNextEntry(new ZipEntry(entry));
                        Files.copy(from, out);
                        out.closeEntry();
                    }
                }
            }
        }
    }
}
