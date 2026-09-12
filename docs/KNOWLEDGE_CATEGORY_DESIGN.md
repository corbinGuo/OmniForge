# 知识库分类设计（2026-09-04，已确认并落地）

> 范围（用户 AskUserQuestion 拍板）：分类来源 = 自动按目录名 + 可手动改（两者都要）；
> 用途 = 面板分组显示 + 检索按分类过滤 + 整类操作。2026-09-04 用户编号确认 7 项全部按默认执行。

## 1. 决策点（请按编号回复 确认/修改）

| # | 决策 | 建议（默认） |
|---|------|------|
| 1 | **存储模型** | 每个向量 chunk 增加 `category` 字段（字符串，空串 = 未分类）。SQLite 表加 `category TEXT NOT NULL DEFAULT ''` 列：启动时 PRAGMA table_info 检查，缺列则 `ALTER TABLE ... ADD COLUMN`（迁移，不丢旧数据）；新建库建表即含。InMemoryVectorStore 并行支持（内存 Map 带 category）。 |
| 2 | **自动归类规则** | 上传单个文件 → category = 该文件所在目录的 basename；上传文件夹 → category = 所选文件夹的 basename（递归子目录不再细分，v1 扁平）。可在上传完成前/后改。 |
| 3 | **手动维护** | 面板每条文档可改 category（输入框/下拉已用过的分类 + 手输），保存即更新该文件全部 chunk 的 category（按 file_name 批量 UPDATE）。删除文档/清空不受影响。 |
| 4 | **面板分组** | 知识库面板按 category 分组展示（未分类排最前/最后），组头可折叠；组头显示该组文件数与「🗑 删除整类」（整类操作）。已用分类下拉建议去重排序。 |
| 5 | **检索过滤** | `knowledge_search` 工具 schema 增加可选 `category` 参数（精确匹配；空 = 全库检索）。KnowledgeService 检索入口透传 category，SQLite/InMemory 余弦打分前先按 category 过滤候选。 |
| 6 | **整类操作** | KnowledgeService 增 `removeByCategory(category)`（该分类全部文件删除 + 对应 chunk 清理）；UI 组头删除调用并刷新。 |
| 7 | **兼容/边界** | 已入库文档迁移后 category=''（显示为未分类），语义不变；向量 BLOB/索引不动；分类为单值字符串（不做多标签，避免复杂），允许包含中文/空格；检索大小写敏感精确匹配（v1）。 |

## 2. 涉及改动

- `SqliteVectorStore`：建表/迁移加列、insert/upsert 携带 category、按 file_name 更新 category、removeByCategory、候选过滤支持 category。
- `InMemoryVectorStore`：同步支持（供降级/测试）。
- `KnowledgeService`：addDocument（从文件路径推 category）、listDocuments 聚合含 category、updateCategory(fileName,cat)、removeByCategory、search 透传 category。
- `knowledge_search` 工具：+category 参数。
- 知识库面板：分组列表 + 折叠 + 改分类 + 整类删除。
- 测试：迁移（旧表无列→ADD）、分类自动/手动/过滤/整类删除/未分类兼容。

## 3. 不做（v1）

- 多标签/层级分类；检索模糊分类；分类重命名级联；备份随分类导出。

## 4. 状态

- [x] 设计确认（2026-09-04 用户编号回复：§1 七项全部按默认）
- [x] 编码 + 测试全绿（知识模块 39 用例；全项目 378 用例全绿，2026-09-04）
- [ ] GUI 验证（用户真机：分组折叠 / 改分类 / 删除整类 / 旧库迁移）
