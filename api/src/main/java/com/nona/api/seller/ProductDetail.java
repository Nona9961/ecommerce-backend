package com.nona.api.seller;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 商品详情响应体（草稿详情查询与编辑的返回形态）。
 * <p>
 * 图片按加入序呈现（primary 标记标识主图，至多一条）；属性按加入序呈现
 * （键值对）。无图片/属性时列表为空（非 null）。
 *
 * @param id          商品 ID
 * @param name        商品名称
 * @param description 商品描述（可空）
 * @param categoryId  平台类目 ID（可空）
 * @param brandId     品牌 ID（可空）
 * @param status      商品状态（DRAFT 草稿 / PENDING_REVIEW 待审核 / ON_SALE 在售）
 * @param images      图片引用列表（按加入序；无图片为空列表）
 * @param attributes  自定义属性列表（按加入序；无属性为空列表）
 *
 * @author nona9961
 */
public record ProductDetail(
        Long id,
        String name,
        String description,
        Long categoryId,
        Long brandId,
        String status,
        List<ProductImageItem> images,
        List<ProductAttributeItem> attributes
) {

    /**
     * 紧凑构造器：防御 null，无图片/属性以空列表呈现。
     *
     * @param id          商品 ID
     * @param name        商品名称
     * @param description 商品描述
     * @param categoryId  平台类目 ID
     * @param brandId     品牌 ID
     * @param status      商品状态
     * @param images      图片引用列表
     * @param attributes  自定义属性列表
     */
    public ProductDetail {
        images = Objects.requireNonNullElse(images, Collections.emptyList());
        attributes = Objects.requireNonNullElse(attributes, Collections.emptyList());
    }
}