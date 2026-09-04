package com.nona.api.seller;

/**
 * 商品图片引用条目响应体（详情内嵌与图片管理响应共用）。
 * <p>
 * URL 为统一形态 {@code /files/{objectKey}}（上传端点产出，业务表只存
 * 该字符串）；primary 标记主图（同一商品至多一条主图，聚合内唯一性保证）。
 *
 * @param id      图片引用 ID
 * @param url     图片 URL（/files/{objectKey} 形态）
 * @param primary 是否主图（至多一条为 true）
 * @author nona9961
 */
public record ProductImageItem(
        Long id,
        String url,
        boolean primary
) {
}