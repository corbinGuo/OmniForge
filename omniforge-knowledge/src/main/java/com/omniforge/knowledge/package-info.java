/**
 * 向量知识库 RAG（com.omniforge.knowledge，Apache 2.0，需求 4.4）。
 *
 * <p>技术实现：文档解析（PDFBox/POI，纯 Java）→ 语义分块（重叠窗口 10%）
 * → DJL + ONNX 向量化（sentence-transformers/all-MiniLM-L6-v2，首启按需下载至
 * ~/.omniforge/models/，决议 #8）→ 向量存储（SQLite 持久化，暴力余弦检索，
 * 2026-09 决议替代 LanceDB，见 docs/LANCE_RETIRED.md；初始化失败降级为进程内存储）
 * → 语义检索（knowledge_search 工具）。</p>
 *
 * <p>分层：
 * <ul>
 *   <li>{@code embedding} —— 向量化 SPI 与 DJL 实现</li>
 *   <li>{@code store} —— 向量存储 SPI（SQLite / 内存降级）</li>
 *   <li>{@code doc} —— 文档解析 SPI（txt/md/PDF/Word）</li>
 *   <li>{@code chunk} —— 语义分块</li>
 *   <li>{@code tool} —— knowledge_search 工具（Tool SPI 接入 Agent 引擎）</li>
 * </ul>
 */
package com.omniforge.knowledge;
