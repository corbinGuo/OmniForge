package com.omniforge.gateway.email;

import java.nio.file.Path;

/**
 * 邮件附件（已流式落盘到临时目录，内存中仅保留元数据）。
 *
 * @param fileName    原始文件名（已清洗非法字符）
 * @param contentType MIME 类型
 * @param sizeBytes   实际大小（字节）
 * @param savedTo     落盘路径
 */
public record EmailAttachment(String fileName, String contentType, long sizeBytes, Path savedTo) {
}
