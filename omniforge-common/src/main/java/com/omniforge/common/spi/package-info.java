/**
 * 工具 SPI 契约。
 *
 * <p>OmniForge 工具生态采用双轨制（需求 4.5）：MCP（模型上下文协议）与 OFT（OmniForge Tool Format）。
 * 本包定义的是引擎内部的统一抽象：{@link com.omniforge.common.spi.Tool} 及其元数据、请求、响应，
 * MCP 与 OFT 规范转换器负责与该抽象互转。插件（plugins/*.jar）通过
 * {@link com.omniforge.common.spi.ToolProvider} 的 ServiceLoader 机制注册工具集。
 */
package com.omniforge.common.spi;
