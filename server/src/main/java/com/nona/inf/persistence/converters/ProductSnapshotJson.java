package com.nona.inf.persistence.converters;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 商品内容快照 JSON 中间形态（product_edit_version.snapshot_json 列承载）：
 * 保存时刻聚合全部内容的字段级全量序列化形态——主体字段 + 图片引用 +
 * 自定义属性 + 规格模板（复用 {@link SpecDimensionJson} 形态）+ SKU 集。
 * <p>
 * 序列化格式：{@code {"name":"无线耳机","description":...,"categoryId":...,"brandId":...,
 * "images":[...],"attributes":[...],"specTemplate":[...],"skus":[...]}}；specTemplate
 * null = 未配置模板（与空模板（0 维度）区分，语义同 product 主表
 * spec_template_json 列）；id 系列字段保留子实体 ID（回滚时 SKU 身份
 * 恢复，跨域引用稳定）。全量快照随每次保存/回滚落一行（体量小，全量
 * 优于增量 patch）。
 *
 * @param name        商品名称
 * @param description 商品描述（可空）
 * @param categoryId  平台类目 ID（可空引用）
 * @param brandId     品牌 ID（可空引用）
 * @param images      图片引用集合（按加入序）
 * @param attributes  自定义属性集合（按加入序）
 * @param specTemplate 规格模板（null=未配置模板）
 * @param skus        SKU 集合（按模板展开序）
 *
 * @author nona9961
 */
public record ProductSnapshotJson(
        String name,
        String description,
        Long categoryId,
        Long brandId,
        List<SnapshotImage> images,
        List<SnapshotAttribute> attributes,
        List<SpecDimensionJson> specTemplate,
        List<SnapshotSku> skus
) {

    /**
     * 紧凑构造器：防御 null，集合以空列表呈现。
     */
    public ProductSnapshotJson {
        images = Objects.requireNonNullElse(images, Collections.emptyList());
        attributes = Objects.requireNonNullElse(attributes, Collections.emptyList());
        skus = Objects.requireNonNullElse(skus, Collections.emptyList());
    }

    /**
     * 商品内容快照 JSON 中间形态（{@link ProductSnapshotJson} 的图片单元）：
     * 图片引用 ID + URL + 主图标记。
     *
     * @param id      图片引用 ID（回滚时身份恢复）
     * @param url     图片 URL（/files/{objectKey} 形态）
     * @param primary 是否主图
     */
    public record SnapshotImage(Long id, String url, boolean primary) {
    }

    /**
     * 商品内容快照 JSON 中间形态（{@link ProductSnapshotJson} 的属性单元）：
     * 属性 ID + 键值。
     *
     * @param id    属性 ID（回滚时身份恢复）
     * @param key   属性键
     * @param value 属性值（可空）
     */
    public record SnapshotAttribute(Long id, String key, String value) {
    }

    /**
     * 商品内容快照 JSON 中间形态（{@link ProductSnapshotJson} 的 SKU 单元）：
     * SKU 身份（ID/组合摘要）+ 价格与启用状态（回滚时完整恢复）。
     *
     * @param id          SKU ID（回滚时身份恢复，跨域引用稳定）
     * @param specHash    规格组合规范化摘要
     * @param specSummary 规格组合可读摘要
     * @param price       销售价（分；null=未定价）
     * @param enabled     是否启用
     */
    public record SnapshotSku(Long id, String specHash, String specSummary, Long price, boolean enabled) {
    }
}