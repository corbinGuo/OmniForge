package com.omniforge.app.market;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.core.agent.SystemInstructionContributor;
import com.omniforge.core.agent.plugin.PluginManager;
import com.omniforge.tools.mcp.McpServerConfig;
import com.omniforge.tools.mcp.McpSettings;
import com.omniforge.tools.mcp.McpSettingsHolder;
import com.omniforge.tools.mcp.McpSettingsStore;
import com.omniforge.ui.plugin.PluginBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件市场协调（P1-3）：本地目录市场 + 三格式全纳管。
 *
 * <p>核心原则：不新增运行时加载器。jar 安装即复制到插件目录（既有
 * {@link PluginManager} WatchService 自动加载/卸载），MCP 安装即并入 mcp.yml
 * （holder 热生效重建连接），Skill 安装即解包到 skills/ 目录并由本类作为
 * {@link SystemInstructionContributor} 在 Agent 调用时并入系统提示。</p>
 *
 * <p>同时实现 {@link PluginBridge}（ui 侧中立动作）与
 * {@link SystemInstructionContributor}（技能指令），两角色由装配层以单 Bean 注入。</p>
 */
public class MarketManager implements PluginBridge, SystemInstructionContributor {

    private static final Logger log = LoggerFactory.getLogger(MarketManager.class);

    /** Skill 指令总量上限：超限整体截断（从头取），日志告警（用户确认 §1#7） */
    static final int MAX_SKILL_CHARS = 8000;

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path pluginsDir;
    private final Path marketDir;
    private final Path skillsDir;
    private final Path stateFile;
    private final Path mcpFile;
    private final PluginManager pluginManager;      // 可空（未装配时插件列表为空）
    private final McpSettingsHolder mcpHolder;      // 可空（MCP 未装配）
    private final McpSettingsStore mcpStore;

    /** 状态缓存（保证并发读写一致，惰性加载：构造不触盘） */
    private MarketState cached;

    public MarketManager(Path pluginsDir, Path marketDir, Path skillsDir, Path stateFile,
                         Path mcpFile, PluginManager pluginManager,
                         McpSettingsStore mcpStore, McpSettingsHolder mcpHolder) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir");
        this.marketDir = Objects.requireNonNull(marketDir, "marketDir");
        this.skillsDir = Objects.requireNonNull(skillsDir, "skillsDir");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.mcpFile = Objects.requireNonNull(mcpFile, "mcpFile");
        this.pluginManager = pluginManager;
        this.mcpStore = Objects.requireNonNull(mcpStore, "mcpStore");
        this.mcpHolder = mcpHolder;
    }

    // ---------- PluginBridge ----------

    @Override
    public Overview overview() {
        ensureDirs();
        List<AvailablePlugin> available = scanPackages().stream()
                .map(pkg -> toAvailable(pkg, currentState().find(pkg.id())))
                .sorted(Comparator.comparing(AvailablePlugin::id))
                .toList();
        return new Overview(available, installedJars(), installedMcp(), installedSkills(),
                marketDir.toAbsolutePath().toString());
    }

    @Override
    public synchronized void install(String id) throws Exception {
        MarketPackage pkg = findPackage(id);
        if (pkg == null) {
            throw new IllegalArgumentException("市场不存在插件包：" + id + "（请先放入市场目录或导入）");
        }
        rejectForbiddenLicense(pkg);
        ensureDirs();
        Path packageDir = packageDirOf(pkg);
        switch (pkg.format()) {
            case MarketPackage.FORMAT_JAR -> installJar(pkg, packageDir);
            case MarketPackage.FORMAT_MCP -> installMcp(pkg, packageDir);
            case MarketPackage.FORMAT_SKILL -> installSkill(pkg, packageDir);
            default -> throw new IllegalArgumentException("不支持的插件格式：" + pkg.format());
        }
        persistState(upsert(currentState(), new MarketState.Item(pkg.format(), pkg.id(),
                pkg.name(), pkg.version(), MarketPackage.FORMAT_SKILL.equals(pkg.format()))));
    }

    @Override
    public synchronized void uninstall(String id) throws Exception {
        ensureDirs();
        MarketState state = currentState();
        MarketState.Item item = state.find(id);
        String format = item != null ? item.format() : detectFormatFromActive(id);
        switch (format) {
            case MarketPackage.FORMAT_JAR -> Files.deleteIfExists(pluginsDir.resolve(id + ".jar"));
            case MarketPackage.FORMAT_MCP -> saveMcp(removeMcp(currentMcp(), id));
            case MarketPackage.FORMAT_SKILL -> deleteRecursively(skillsDir.resolve(id));
            default -> throw new IllegalArgumentException("找不到可卸载项：" + id);
        }
        if (item != null) {
            persistState(removeItem(state, id));
        }
        log.info("已卸载：{}（{}）", id, format);
    }

    @Override
    public synchronized void rollback(String id) throws Exception {
        MarketState.Item item = currentState().find(id);
        if (item == null || !MarketPackage.FORMAT_JAR.equals(item.format())) {
            throw new IllegalArgumentException("回滚仅支持市场安装的 jar 插件（当前无记录）：" + id);
        }
        Path previousJar = archiveDir(id).resolve("previous.jar");
        if (!Files.isRegularFile(previousJar)) {
            throw new IllegalArgumentException("无可用回滚版本（未检测到上一活跃版）：" + id);
        }
        Files.copy(previousJar, pluginsDir.resolve(id + ".jar"),
                StandardCopyOption.REPLACE_EXISTING);
        persistState(upsert(currentState(), readArchiveMeta(id, item)));
        log.info("插件已回滚：{} → {}", id, readArchiveMeta(id, item).version());
    }

    @Override
    public synchronized void setEnabled(String format, String id, boolean enabled) throws Exception {
        ensureDirs();
        switch (format) {
            case MarketPackage.FORMAT_MCP -> {
                List<McpServerConfig> servers = new ArrayList<>(currentMcp().servers());
                boolean found = false;
                for (int i = 0; i < servers.size(); i++) {
                    McpServerConfig config = servers.get(i);
                    if (id.equals(config.name())) {
                        servers.set(i, new McpServerConfig(config.name(), config.transport(),
                                config.command(), config.args(), config.url(), enabled));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("MCP 服务器不存在：" + id);
                }
                saveMcp(new McpSettings(servers));
            }
            case MarketPackage.FORMAT_SKILL -> {
                MarketState state = currentState();
                MarketState.Item item = state.find(id);
                if (item == null || !MarketPackage.FORMAT_SKILL.equals(item.format())) {
                    throw new IllegalArgumentException("技能不存在（需先安装）：" + id);
                }
                persistState(upsert(state, new MarketState.Item(item.format(), item.id(),
                        item.name(), item.version(), enabled)));
            }
            default -> throw new IllegalArgumentException("该格式不支持启停：" + format);
        }
    }

    @Override
    public void importPackage(Path archive) throws Exception {
        Objects.requireNonNull(archive, "archive");
        if (Files.isDirectory(archive)) {
            importDirectory(archive);
            return;
        }
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().endsWith(".zip")) {
            throw new IllegalArgumentException("请提供插件包目录或 .zip 压缩包：" + archive);
        }
        Path staging = Files.createTempDirectory("omniforge-market-import");
        try {
            unzip(archive, staging);
            importDirectory(staging);
            log.info("插件包已导入市场：{}", archive.getFileName());
        } finally {
            deleteRecursively(staging);
        }
    }

    // ---------- SystemInstructionContributor ----------

    @Override
    public String extraSystemText() {
        ensureDirs();
        List<MarketState.Item> enabled = currentState().items().stream()
                .filter(item -> MarketPackage.FORMAT_SKILL.equals(item.format()) && item.enabled())
                .sorted(Comparator.comparing(MarketState.Item::id))
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        for (MarketState.Item skill : enabled) {
            String body = skillBody(skill.id());
            if (body == null || body.isBlank()) {
                continue;
            }
            if (block.length() > 0) {
                block.append("\n\n");
            }
            block.append("【技能：").append(skill.name()).append("】仅当任务与之相关时遵循：\n").append(body);
        }
        if (block.isEmpty()) {
            return null;
        }
        if (block.length() > MAX_SKILL_CHARS) {
            log.warn("启用技能指令总长 {} 字超过上限 {}，已从头截断（请精简技能文档）",
                    block.length(), MAX_SKILL_CHARS);
            return block.substring(0, MAX_SKILL_CHARS);
        }
        return block.toString();
    }

    // ---------- 快照辅助 ----------

    private AvailablePlugin toAvailable(MarketPackage pkg, MarketState.Item installed) {
        String state;
        if (installed == null) {
            state = PluginBridge.STATE_NOT_INSTALLED;
        } else if (!installed.version().equals(pkg.version())) {
            state = PluginBridge.STATE_UPGRADABLE;
        } else {
            state = PluginBridge.STATE_INSTALLED;
        }
        return new AvailablePlugin(pkg.format(), pkg.id(), pkg.name(), pkg.version(),
                pkg.description(), pkg.author(), pkg.license(), state);
    }

    private List<InstalledJar> installedJars() {
        if (pluginManager == null) {
            return List.of();
        }
        return pluginManager.listLoaded().stream()
                .map(loaded -> {
                    String fileName = loaded.jarFileName();
                    String id = fileName.endsWith(".jar")
                            ? fileName.substring(0, fileName.length() - ".jar".length()) : fileName;
                    return new InstalledJar(id, fileName,
                            List.copyOf(loaded.pluginNames()), List.copyOf(loaded.toolNames()));
                })
                .toList();
    }

    private List<InstalledMcp> installedMcp() {
        return currentMcp().servers().stream()
                .map(server -> new InstalledMcp(server.name(), server.transport(), server.enabled()))
                .toList();
    }

    private List<InstalledSkill> installedSkills() {
        MarketState state = currentState();
        List<InstalledSkill> skills = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            return skills;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("SKILL.md"))) {
                    continue;
                }
                String id = dir.getFileName().toString();
                MarketState.Item item = state.find(id);
                skills.add(new InstalledSkill(id, skillDescription(id),
                        item != null && item.enabled()));
            }
        } catch (IOException e) {
            log.warn("读取技能目录失败：{}", e.getMessage());
        }
        return skills.stream().sorted(Comparator.comparing(InstalledSkill::name)).toList();
    }

    // ---------- 三格式安装 ----------

    private void installJar(MarketPackage pkg, Path packageDir) throws IOException {
        Path payload = pkg.resolvePayload(packageDir);
        if (payload == null || !Files.isRegularFile(payload)
                || !payload.getFileName().toString().endsWith(".jar")) {
            throw new IllegalArgumentException("jar 插件包缺少合法 payload（.jar）：" + pkg.id());
        }
        Files.createDirectories(pluginsDir);
        Path target = pluginsDir.resolve(pkg.id() + ".jar");
        if (Files.exists(target)) {
            archiveCurrentJar(target, pkg.id());
        }
        Files.copy(payload, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("插件已安装：{}（jar，v{}）", pkg.id(), pkg.version());
    }

    /** 升级前把当前活跃 jar 归档为上一版（previous.jar + previous.json 记录原版本） */
    private void archiveCurrentJar(Path activeJar, String id) throws IOException {
        Path archiveDir = archiveDir(id);
        Files.createDirectories(archiveDir);
        Files.copy(activeJar, archiveDir.resolve("previous.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        MarketState state = currentState();
        MarketState.Item installed = state.find(id);
        MarketPackage embedded = MarketPackage.readEmbedded(activeJar);
        String oldVersion = installed != null ? installed.version()
                : (embedded != null ? embedded.version() : "0");
        String oldName = embedded != null && !embedded.name().isBlank() ? embedded.name() : id;
        new MarketStateStore().save(archiveDir.resolve("previous.json"),
                new MarketState(List.of(new MarketState.Item(MarketPackage.FORMAT_JAR,
                        id, oldName, oldVersion, true))));
    }

    private MarketState.Item readArchiveMeta(String id, MarketState.Item fallback) {
        MarketState archived = new MarketStateStore().load(archiveDir(id).resolve("previous.json"));
        MarketState.Item item = archived.find(id);
        if (item != null) {
            return item;
        }
        MarketPackage embedded = MarketPackage.readEmbedded(pluginsDir.resolve(id + ".jar"));
        String version = embedded != null ? embedded.version() : fallback.version();
        return new MarketState.Item(fallback.format(), fallback.id(), fallback.name(),
                version, fallback.enabled());
    }

    private void installMcp(MarketPackage pkg, Path packageDir) throws Exception {
        Path payload = pkg.resolvePayload(packageDir);
        if (payload == null || !Files.isRegularFile(payload)) {
            throw new IllegalArgumentException("mcp 插件包缺少合法 payload（mcp-server.json）：" + pkg.id());
        }
        McpPayload incoming;
        try {
            incoming = McpPayload.read(payload);
        } catch (Exception e) {
            // Jackson 原始报错含 REDACTED 定位噪音，包装为面向用户的明确信息
            throw new IllegalArgumentException("mcp 插件包 payload 不是合法 JSON（" + pkg.id()
                    + "）：请检查转义——Windows 路径建议用正斜杠 C:/a/b 或双反斜杠。原错误：" + e.getMessage(), e);
        }
        McpServerConfig server = new McpServerConfig(pkg.id(), incoming.transport(),
                incoming.command(), incoming.args(), incoming.url(), true);
        List<McpServerConfig> servers = new ArrayList<>(currentMcp().servers());
        servers.removeIf(existing -> pkg.id().equals(existing.name()));
        servers.add(server);
        saveMcp(new McpSettings(servers));
        log.info("MCP 服务器已安装：{}（v{}）", pkg.id(), pkg.version());
    }

    private void installSkill(MarketPackage pkg, Path packageDir) throws IOException {
        Path payload = pkg.resolvePayload(packageDir);
        if (payload == null || !Files.isDirectory(payload)
                || !Files.isRegularFile(payload.resolve("SKILL.md"))) {
            throw new IllegalArgumentException("技能插件包缺少含 SKILL.md 的 payload 目录：" + pkg.id());
        }
        Path target = skillsDir.resolve(pkg.id());
        deleteRecursively(target);
        Files.createDirectories(skillsDir);
        copyDirectory(payload, target);
        log.info("技能已安装：{}（v{}）", pkg.id(), pkg.version());
    }

    // ---------- MCP 设置 ----------

    private McpSettings currentMcp() {
        return mcpHolder != null ? mcpHolder.current() : mcpStore.load(mcpFile);
    }

    private void saveMcp(McpSettings settings) throws Exception {
        mcpStore.save(mcpFile, settings);
        if (mcpHolder != null) {
            mcpHolder.update(settings);
        }
    }

    private static McpSettings removeMcp(McpSettings settings, String name) {
        return new McpSettings(settings.servers().stream()
                .filter(server -> !name.equals(server.name()))
                .toList());
    }

    /** mcp 市场载荷：与 mcp.yml 服务器条目同构（不含 enabled，安装即启用） */
    private record McpPayload(String name, String transport, String command,
                              List<String> args, String url) {
        static McpPayload read(Path file) throws IOException {
            return JSON.readValue(Files.readAllBytes(file), McpPayload.class);
        }
    }

    // ---------- 状态 ----------

    private synchronized MarketState currentState() {
        if (cached == null) {
            ensureDirs();
            cached = new MarketStateStore().load(stateFile);
        }
        return cached;
    }

    private synchronized void persistState(MarketState state) {
        try {
            new MarketStateStore().save(stateFile, state);
            cached = state;
        } catch (IOException e) {
            log.warn("市场状态保存失败（本次操作仍已生效）：{}", e.getMessage());
        }
    }

    private static MarketState upsert(MarketState state, MarketState.Item item) {
        List<MarketState.Item> items = new ArrayList<>(state.items());
        items.removeIf(existing -> item.id().equals(existing.id()));
        items.add(item);
        return new MarketState(items);
    }

    private static MarketState removeItem(MarketState state, String id) {
        return new MarketState(state.items().stream()
                .filter(item -> !id.equals(item.id()))
                .toList());
    }

    // ---------- 扫描 ----------

    private List<MarketPackage> scanPackages() {
        ensureDirs();
        List<MarketPackage> packages = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(marketDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                MarketPackage pkg = MarketPackage.read(dir);
                if (pkg == null || pkg.id().isBlank()) {
                    log.warn("跳过无效插件包目录（缺 package.manifest.json 或 id 为空）：{}",
                            dir.getFileName());
                    continue;
                }
                packages.add(pkg);
            }
        } catch (IOException e) {
            log.warn("扫描市场目录失败：{}", e.getMessage());
        }
        return packages;
    }

    private MarketPackage findPackage(String id) {
        return scanPackages().stream().filter(pkg -> id.equals(pkg.id())).findFirst().orElse(null);
    }

    /** 包目录：以 id 命名的市场子目录（导入/放置规范）；否则回退市场根（兼容散放） */
    private Path packageDirOf(MarketPackage pkg) {
        Path dir = marketDir.resolve(pkg.id());
        return Files.isDirectory(dir) ? dir : marketDir;
    }

    // ---------- 技能内容 ----------

    private String skillDescription(String id) {
        String body = readSkillFile(id);
        if (body == null) {
            return "";
        }
        String description = SkillText.frontmatterField(body, "description");
        if (description != null && !description.isBlank()) {
            return description;
        }
        String text = SkillText.body(body);
        if (text == null) {
            return "";
        }
        String first = text.lines().filter(s -> !s.isBlank()).findFirst().orElse("");
        return first.length() > 120 ? first.substring(0, 120) + "…" : first;
    }

    private String skillBody(String id) {
        String file = readSkillFile(id);
        return file == null ? null : SkillText.body(file);
    }

    private String readSkillFile(String id) {
        Path skill = skillsDir.resolve(id).resolve("SKILL.md");
        if (!Files.isRegularFile(skill)) {
            return null;
        }
        try {
            return Files.readString(skill);
        } catch (IOException e) {
            log.warn("读取技能 SKILL.md 失败：{}：{}", id, e.getMessage());
            return null;
        }
    }

    /** SKILL.md 解析：剥离 YAML frontmatter 取正文 / 按键取 frontmatter 字段 */
    private static final class SkillText {

        static String body(String content) {
            int end = frontmatterEnd(content);
            String text = end < 0 ? content : content.substring(end);
            return text.isBlank() ? null : text.strip();
        }

        static String frontmatterField(String content, String key) {
            if (!content.startsWith("---")) {
                return null;
            }
            int end = content.indexOf("\n---", 3);
            if (end < 0) {
                return null;
            }
            String front = content.substring(3, end);
            for (String line : front.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon > 0 && key.equals(line.substring(0, colon).strip())) {
                    String value = line.substring(colon + 1).strip();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
            return null;
        }

        /** frontmatter 结束后的首个换行下标（无 frontmatter 返回 -1） */
        private static int frontmatterEnd(String content) {
            if (!content.startsWith("---")) {
                return -1;
            }
            int end = content.indexOf("\n---", 3);
            if (end < 0) {
                return -1;
            }
            return end + 4; // 越过 "\n---"
        }
    }

    // ---------- 许可校验 ----------

    private static void rejectForbiddenLicense(MarketPackage pkg) {
        String license = pkg.license();
        if (license == null || license.isBlank()) {
            return;
        }
        String lower = license.toLowerCase();
        if (lower.contains("agpl") || lower.contains("gpl")) {
            throw new IllegalArgumentException(
                    "插件许可「" + license + "」为强传染 GPL/AGPL，禁止安装（开源合规底线）");
        }
    }

    // ---------- 导入 ----------

    private void importDirectory(Path source) throws Exception {
        MarketPackage pkg = MarketPackage.read(source);
        if (pkg == null || pkg.id().isBlank()) {
            throw new IllegalArgumentException("导入源损坏或缺少 id：" + source);
        }
        Path target = marketDir.resolve(pkg.id());
        deleteRecursively(target);
        Files.createDirectories(marketDir);
        copyDirectory(source, target);
    }

    private static void unzip(Path zip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("压缩包含越界路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zipIn, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zipIn.closeEntry();
            }
        }
    }

    // ---------- 目录/文件工具 ----------

    private void ensureDirs() {
        for (Path dir : List.of(marketDir, skillsDir, pluginsDir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.warn("创建目录失败：{}：{}", dir, e.getMessage());
            }
        }
    }

    private Path archiveDir(String id) {
        return marketDir.resolve("archive").resolve(id);
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path from : stream.sorted().toList()) {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("删除目录失败：{}：{}", root, e.getMessage());
        }
    }

    /** 卸载时 state 缺失的格式兜底（按活跃态推断） */
    private String detectFormatFromActive(String id) {
        if (Files.isRegularFile(pluginsDir.resolve(id + ".jar"))) {
            return MarketPackage.FORMAT_JAR;
        }
        if (Files.isDirectory(skillsDir.resolve(id))) {
            return MarketPackage.FORMAT_SKILL;
        }
        if (currentMcp().servers().stream().anyMatch(server -> id.equals(server.name()))) {
            return MarketPackage.FORMAT_MCP;
        }
        return "";
    }
}
