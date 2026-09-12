package com.omniforge.app.market;

import java.util.List;

/**
 * 市场安装状态（state.json）：记录由市场安装/管理的各条目当前版本与技能启用标记。
 *
 * <p>jar/mcp 的“活跃态”以插件目录文件与 mcp.yml 为准（state 仅记市场来源的
 * 版本与用途），技能启用标记需持久化，故与活跃态分离记录于此。</p>
 *
 * @param items 已安装条目
 */
public record MarketState(List<Item> items) {

    /** 单条安装记录 */
    public record Item(String format, String id, String name, String version, boolean enabled) {
        public Item {
            format = format == null ? "" : format.strip();
            id = id == null ? "" : id.strip();
            name = name == null ? "" : name.strip();
            version = version == null ? "0" : version.strip();
        }
    }

    public MarketState {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static MarketState empty() {
        return new MarketState(List.of());
    }

    /** 按 id 查条目 */
    public Item find(String id) {
        return items.stream().filter(item -> id.equals(item.id())).findFirst().orElse(null);
    }
}
