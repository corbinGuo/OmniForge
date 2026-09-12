package com.omniforge.app.market;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 市场插件包描述（package.manifest.json / jar 内嵌 META-INF/omniforge-plugin.json）。
 *
 * <p>同构结构也是未来服务端 CatalogEntry 的本地映射（P1-3 §3/§8）。三格式：
 * <ul>
 *   <li>jar：载荷为包内 plugin.jar 相对路径；</li>
 *   <li>mcp：载荷为包内 mcp-server.json（{@code name/transport/command/args/url}）；</li>
 *   <li>skill：载荷为包内技能目录（含 SKILL.md）。</li>
 * </ul>
 * 缺失/损坏的包在扫描时跳过并告警，不阻断市场。</p>
 */
public record MarketPackage(String format, String id, String name, String version,
                            String description, String author, String license, String payload) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static final String MANIFEST_FILE = "package.manifest.json";
    public static final String EMBEDDED_PATH = "META-INF/omniforge-plugin.json";
    public static final String FORMAT_JAR = "jar";
    public static final String FORMAT_MCP = "mcp";
    public static final String FORMAT_SKILL = "skill";

    public MarketPackage {
        format = format == null ? "" : format.strip();
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        version = version == null ? "0" : version.strip();
        description = description == null ? "" : description.strip();
        author = author == null ? "" : author.strip();
        license = license == null ? "" : license.strip();
        payload = payload == null ? "" : payload.strip();
    }

    /** 读取市场包目录的 package.manifest.json；缺失/损坏返回 null（调用方跳过+告警） */
    public static MarketPackage read(Path packageDir) {
        try {
            Path manifest = packageDir.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(manifest)) {
                return null;
            }
            return JSON.readValue(Files.readAllBytes(manifest), MarketPackage.class);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 读取已安装 jar 内嵌 manifest（可选元数据）；无/损坏返回 null（降级为运行时 SPI 信息） */
    public static MarketPackage readEmbedded(Path jarFile) {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            JarEntry entry = jar.getJarEntry(EMBEDDED_PATH);
            if (entry == null) {
                return null;
            }
            try (var in = jar.getInputStream(entry)) {
                return JSON.readValue(in.readAllBytes(), MarketPackage.class);
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 载荷绝对路径（越界防护：归一化后必须仍在包目录内） */
    public Path resolvePayload(Path packageDir) {
        if (payload.isBlank()) {
            return null;
        }
        Path base = packageDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(payload).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }
}
