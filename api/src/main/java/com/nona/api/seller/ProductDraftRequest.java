package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 商品草稿请求体：创建与主体编辑共用的契约。
 * <p>
 * 草稿语义：名称必填（无名草稿拒绝保存），描述/平台类目/品牌均可空
 * （草稿允许不完整，未提交审核不生效）；类目/品牌引用存在性校验在用例层
 * （引用非空时目标必须存在且启用）。图片与自定义属性走独立子资源端点管理
 * （本请求体不承载集合——图片增删/设主图、属性键值增删改各自独立变更面）。
 *
 * @param name        商品名称（必填）
 * @param description 商品描述（可空）
 * @param categoryId  平台类目 ID（可空；非空时目标必须存在且启用）
 * @param brandId     品牌 ID（可空；非空时目标必须存在且启用）
 */
public record ProductDraftRequest(
        @NotBlank(message = "商品名称不能为空") String name,
        String description,
        Long categoryId,
        Long brandId
) {
}