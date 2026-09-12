/**
 * 工具生态（com.omniforge.tools，Apache 2.0，需求 4.5）。
 *
 * <p>双轨制：MCP（模型上下文协议）与 OFT（OmniForge Tool Format）；
 * 本模块实现内置工具与安全护栏，统一以 common 模块的 Tool SPI 暴露，
 * 经 {@link com.omniforge.tools.builtin.BuiltinToolProvider}（ServiceLoader）注册进引擎。</p>
 *
 * <p>内置工具与安全约束（需求 4.5 内置工具清单）：
 * <ul>
 *   <li>{@code web_search} —— Tavily / SearXNG HTTP API，URL 安全校验（防 SSRF）；</li>
 *   <li>{@code file_read_write} —— 工作区沙箱（禁止访问工作区外路径）；</li>
 *   <li>{@code python_interpreter} —— PythonEngine SPI + 黑名单拦截；JEP 实现在 omniforge-tools-python；</li>
 *   <li>{@code shell_executor} —— 默认禁用，需显式开启；危险命令黑名单。</li>
 * </ul>
 */
package com.omniforge.tools;
