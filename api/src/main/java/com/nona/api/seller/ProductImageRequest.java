package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 商品图片添加请求体。
 * <p>
 * primary 默认 false；显式传 true 时替换现有主图（清除原主图标记，
 * 至多一条主图）。URL 必须为上传端点返回的 {@code /files/{objectKey}}
 * 形态字符串——本端点只管理引用，不做文件移动。
 *
 * @param url    图片 URL（必填）
 * @param primary 是否设为主图（可空，默认 false）
 * @author nona9961
 */
public record ProductImageRequest(
        @NotBlank(message = "图片URL不能为空") String url,
        Boolean primary
) {
}