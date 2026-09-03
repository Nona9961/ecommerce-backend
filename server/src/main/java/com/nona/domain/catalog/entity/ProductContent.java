package com.nona.domain.catalog.entity;

import java.util.List;
import java.util.Objects;

/**
 * 商品内容载体（回滚输入）：Product 聚合全量内容的不可变值对象形态——
 * 主体字段 + 图片引用集合 + 自定义属性集合 + 规格模板 + SKU 集合。
 * <p>
 * 由历史版本快照反序列化重建（回滚路径）或当前聚合导出（快照序列化
 * 路径）生成，作为聚合整体内容重置的唯一输入（{@code restoreContent}）；
 * 集合以防御拷贝持有（入参集合后续变动不影响载体，SKU/图片/属性实体
 * 引用本身按实体语义共享）。
 * <p>
 * 语义约定：图片/属性/SKU 保持各自集合序（快照序）；规格模板
 * null = 尚未配置模板（SKU 集随之为空）；其余字段可空（草稿允许
 * 不完整，名称必填校验在聚合重置路径）。
 *
 * @param name        商品名称（必填非空，聚合路径校验）
 * @param description 商品描述（可空）
 * @param categoryId  平台类目 ID（可空引用）
 * @param brandId     品牌 ID（可空引用）
 * @param images      图片引用集合（保持集合序）
 * @param attributes  自定义属性集合（保持集合序）
 * @param specTemplate 规格模板（null=未配置模板）
 * @param skus        SKU 集合（保持集合序；模板未配置时为空）
 *
 * @author nona9961
 */
public record ProductContent(
        String name,
        String description,
        Long categoryId,
        Long brandId,
        List<ProductImage> images,
        List<ProductAttribute> attributes,
        SpecTemplate specTemplate,
        List<Sku> skus
) {

    /**
     * 紧凑构造器：防御 null，集合以不可变副本呈现（空集合 = 无子项）。
     */
    public ProductContent {
        images = List.copyOf(Objects.requireNonNullElse(images, List.of()));
        attributes = List.copyOf(Objects.requireNonNullElse(attributes, List.of()));
        skus = List.copyOf(Objects.requireNonNullElse(skus, List.of()));
    }
}