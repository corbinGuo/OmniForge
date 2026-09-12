package com.omniforge.core.gateway.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分层级路由配置持久化（routing.yml，Batch2）。
 *
 * <p>兼容旧 7 键双 alias 文件：enabled/threshold/weights/keywords/exemplars 直接沿用，
 * 旧 simpleAlias/advancedAlias 读入 legacy 字段（未分级迁移兜底）；
 * 新键 tierNames/defaultStrategy/rules 缺失时归一化默认。文件缺失写默认模板；
 * 损坏返回默认。绝不抛出。</p>
 */
public class RoutingConfigStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取；文件缺失 → 写默认模板并返回默认；损坏/根非对象 → 默认 */
    public RoutingConfig load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            try {
                save(file, RoutingConfig.defaults());
            } catch (IOException e) {
                // 模板写入失败不影响默认行为
            }
            return RoutingConfig.defaults();
        }
        try {
            return fromNode(yamlMapper.readTree(Files.readAllBytes(file)));
        } catch (IOException e) {
            return RoutingConfig.defaults();
        }
    }

    /** 保存（自动创建父目录；record 紧凑构造保证字段归一化） */
    public void save(Path file, RoutingConfig settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }

    private static RoutingConfig fromNode(JsonNode root) {
        RoutingConfig defaults = RoutingConfig.defaults();
        if (root == null || !root.isObject()) {
            return defaults;
        }
        boolean enabled = root.path("enabled").asBoolean(defaults.isEnabled());
        double threshold = root.path("threshold").asDouble(defaults.getThreshold());
        Map<String, Double> weights = readWeights(root.get("weights"), defaults.getWeights());
        List<String> keywords = readTextArray(root.get("keywords"));
        List<String> exemplars = readTextArray(root.get("exemplars"));
        Map<Integer, String> tierNames = readTierNames(root.get("tierNames"), defaults.getTierNames());
        RoutingStrategy defaultStrategy = parseStrategy(root.get("defaultStrategy"));
        List<RoutingRule> rules = readRules(root.get("rules"));
        // 兼容两键名：优先读自身 canonical（legacy*），回退旧双 alias 键
        String legacySimple = firstText(root.get("legacySimpleAlias"), root.get("simpleAlias"));
        String legacyAdvanced = firstText(root.get("legacyAdvancedAlias"), root.get("advancedAlias"));
        return new RoutingConfig(enabled, threshold, weights,
                keywords.isEmpty() ? defaults.getKeywords() : keywords,
                exemplars.isEmpty() ? defaults.getExemplars() : exemplars,
                tierNames, defaultStrategy, rules,
                legacySimple == null ? defaults.getLegacySimpleAlias() : legacySimple,
                legacyAdvanced == null ? defaults.getLegacyAdvancedAlias() : legacyAdvanced);
    }

    private static Map<String, Double> readWeights(JsonNode node, Map<String, Double> fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        Map<String, Double> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), e.getValue().asDouble(0)));
        return map.isEmpty() ? fallback : map;
    }

    private static List<String> readTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        node.forEach(n -> {
            if (n.isTextual()) {
                list.add(n.asText());
            }
        });
        return list;
    }

    private static Map<Integer, String> readTierNames(JsonNode node, Map<Integer, String> fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        Map<Integer, String> map = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            try {
                map.put(Integer.parseInt(e.getKey()), e.getValue().asText());
            } catch (NumberFormatException ignored) {
                // 非法层级键忽略
            }
        });
        return map.isEmpty() ? fallback : map;
    }

    private static List<RoutingRule> readRules(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<RoutingRule> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            RoutingStrategy strategy = parseStrategy(item.get("strategy"));
            list.add(new RoutingRule(item.path("priority").asInt(1000),
                    item.path("name").asText(""),
                    item.path("identityPattern").asText("*"),
                    strategy));
        }
        return list;
    }

    private static RoutingStrategy parseStrategy(JsonNode node) {
        if (node == null || !node.isObject()) {
            return RoutingStrategy.auto(null);
        }
        String type = node.path("type").asText("").toUpperCase();
        try {
            RoutingStrategyType strategyType = RoutingStrategyType.valueOf(type);
            return new RoutingStrategy(strategyType,
                    intOrNull(node, "minTier"),
                    intOrNull(node, "maxTier"),
                    intOrNull(node, "fixedTier"),
                    readIntArray(node.get("excludeTiers")));
        } catch (IllegalArgumentException e) {
            return RoutingStrategy.auto(null);
        }
    }

    private static List<Integer> readIntArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> list = new ArrayList<>();
        node.forEach(n -> {
            if (n.isNumber()) {
                list.add(n.asInt());
            }
        });
        return list;
    }

    private static Integer intOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** 取第一个存在的文本（旧键迁移：canonical 优先，旧键兜底） */
    private static String firstText(JsonNode primary, JsonNode legacy) {
        String value = textOrNull(primary);
        return value == null ? textOrNull(legacy) : value;
    }
}
