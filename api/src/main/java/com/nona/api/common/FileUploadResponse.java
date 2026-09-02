package com.nona.api.common;

/**
 * 文件上传结果：统一 URL 形态，业务表只存该字符串（订单快照同规则）。
 *
 * @param url 图片可访问 URL（GET {@code /files/{objectKey}} 公开读）
 * @author nona9961
 */
public record FileUploadResponse(String url) {
}