# 知识库文件格式扩展设计（2026-09-04，待用户编号确认）

> 诉求（用户原话）：知识库目前只支持 txt/md/pdf/docx，像 Excel、jpeg 等内容不支持，
> 需要尽可能全地支持各种类型文件。
> 约束：解析器全部纯 Java、Apache/MIT 许可（禁 GPL 强传染）；桌面单机，文本语义检索。

## 1. 决策点（请按编号回复 确认/修改）

| # | 决策 | 默认建议 |
|---|------|---------|
| 1 | **新式 Office**：`.xlsx`/`.xls`（Excel）、`.pptx`（PowerPoint） | 支持。用现有 Apache POI 5.3.0（poi-ooxml 已含 XSSF/HSSF/XMLSlideShow），**零新增依赖**。逐 sheet/幻灯片提取文本（`DataFormatter` 按单元格显示格式取值），sheet/幻灯片间用「【Sheet: 名】/【幻灯片 N】」标记分隔。 |
| 2 | **旧版二进制 Office**：`.doc`（Word）、`.ppt`（PowerPoint） | 支持。需新增同族 `org.apache.poi:poi-scratchpad:5.3.0`（Apache-2.0；本地仓库无 5.3.0 缓存，**需联网拉取一次**，走 aliyun 镜像）。HWPF(.doc) / HSLF(.ppt) 提取。 |
| 3 | **文本类白名单扩宽 + 编码兜底** | 支持。`PlainTextParser` 扩展名扩到常见 数据/配置/源码/网页 文本：`txt md markdown log csv tsv json xml yml yaml properties ini conf cfg toml sql htm html css js ts bat cmd ps1 sh py java c cpp h` 等；解析先按 UTF-8 严格解码，失败自动降级 GB18030（中文 Excel/CSV 导出常见）。 |
| 4 | **HTML/RTF 轻解析（不加第三方依赖）** | 支持。html/htm：去除 `<script>/<style>` 块后剥标签得正文；rtf：内置剥离 RTF 控制字/分组、保留字面文本。如需规范解析（JSoup，MIT）另议。 |
| 5 | **图片与无文本层文档**：`jpeg/jpg/png/bmp/gif/webp/tiff` 与扫描版 PDF/DOCX/XLSX | **暂不支持，界面友好提示**（无文本层无法检索）。OCR 单独评估（Tess4J+本机 Tesseract，或 Windows 内置 OCR——均有外部/系统依赖，需你确认再排）。 |
| 6 | **杂项护栏** | ① 文件夹递归扫描过滤 Office 临时文件（`~$` 前缀）；② 上传文件选择器扩展名过滤、文件夹扫描、面板提示文案全部随解析能力同步；③ 单文件提取文本护栏：上限约 200 万字符（约 1 个 Embedding 模型 token 上限量级），超出截断并记日志，防超大 Excel/PPT 拖垮桌面。 |

## 2. 方案映射表

| 扩展名 | 解析器（新增为 ★） | 依赖 | 许可 | 提取方式 |
|--------|------------------|------|------|---------|
| txt/md/markdown/log/csv/tsv/json/xml/yml/yaml/properties + 文本宽化清单 | PlainTextParser（改） | 无 | - | UTF-8→GB18030 兜底 |
| html/htm | PlainTextParser+HTML 轻解析（内置★） | 无 | - | 去 script/style + 剥标签 |
| rtf | RtfParser（内置★） | 无 | - | 剥 RTF 控制字 |
| pdf | PdfParser | PDFBox 3.0.3 | Apache-2.0 | 文本层抽取；空→提示扫描版 |
| docx | DocxParser | POI ooxml 5.3.0 | Apache-2.0 | 段落文本 |
| xlsx | OfficeTextParser（★） | POI ooxml 5.3.0 | Apache-2.0 | XSSF 逐表逐行 |
| xls | OfficeTextParser（★） | POI 5.3.0（已有传递依赖） | Apache-2.0 | HSSF 逐表逐行 |
| pptx | OfficeTextParser（★） | POI ooxml 5.3.0 | Apache-2.0 | XMLSlideShow 逐页文本框 |
| doc | WordLegacyParser（★） | poi-scratchpad 5.3.0（新） | Apache-2.0 | HWPF WordExtractor |
| ppt | PptLegacyParser（★） | poi-scratchpad 5.3.0（新） | Apache-2.0 | HSLF 逐页文本框 |
| jpeg/jpg/png/bmp/gif/webp/tiff | （不支持，UI 提示） | - | - | 无文本层，需 OCR |
| ~$* 临时文件 | （过滤） | - | - | Office 打开时残留 |

> 解析器会统一导出为静态工厂（`DocumentParsers.defaults()`），Spring 装配与
> `KnowledgeToolProvider`（ServiceLoader/stdio MCP 路径）共用，避免两处清单漂移。

## 3. 涉及改动

- `omniforge-knowledge/doc`：`OfficeTextParser`（xlsx/xls/pptx）、`WordLegacyParser`(.doc)、
  `PptLegacyParser`(.ppt)、`RtfParser`(.rtf)；`PlainTextParser` 扩白名单 + HTML 轻解析 + 编码兜底；
  抽 `DocumentParsers.defaults()` 统一清单。
- `pom.xml`（knowledge 模块）：按决策 #2 增 `poi-scratchpad`。
- `KnowledgeAutoConfiguration` / `KnowledgeToolProvider`：解析器清单换用 `DocumentParsers.defaults()`。
- `KnowledgeService`：无接口改动；解析失败信息保持"继续下一个"。
- 知识库面板：`SUPPORTED_EXT`（文件选择器 + 文件夹扫描）、提示文案、`~$` 过滤。
- 测试：Excel(.xlsx/.xls)/PPT(.pptx/.ppt)/.doc/RTF/HTML/GBK 文本 各解析用例 + 编码兜底 + 临时文件过滤。

## 4. 不做（本版）

- 图片 OCR 与扫描件文字识别（见决策 #5，单独排期）。
- 云端/格式转换服务；.odt/.ods/.epub/.msg/.eml 等小众格式。
- 图像/附件原文件入库但不参与检索。

## 5. 状态

- [x] 设计确认（2026-09-05 用户编号回复：§1 六项全按默认 + 参数 单文件≤10MB/单文件≤50chunk/单次上传≤100MB；
  .odt/.ods/.odp/.wps/.et/.dps 记为后续扩展项）
- [x] 编码 + 测试全绿（全项目 388 用例全绿，knowledge 49；2026-09-05）
- [ ] GUI 验证（用户真机上传各格式）
