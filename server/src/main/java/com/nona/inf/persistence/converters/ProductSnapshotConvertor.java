package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductContent;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.JacksonUtil;
import org.springframework.stereotype.Component;

/**
 * 商品内容快照转换器：聚合 ↔ 全量快照 JSON（product_edit_version 的
 * snapshot_json 列）↔ 内容载体（回滚输入）的双向转换契约。
 * <p>
 * 序列化：聚合当前全部内容（主体 + 图片引用 + 自定义属性 + 规格模板 +
 * SKU 集）经 {@link ProductSnapshotJson} 中间形态落 JSON（复用项目统一
 * JacksonUtil 能力，不经变更追踪快照树——追踪快照仅服务变更集 diff，
 * 版本快照是独立的稳定持久化形态）。
 * 反序列化：snapshot_json → 中间形态 → {@link ProductContent} 内容载体
 * （回滚路径输入，由聚合 {@code restoreContent} 装载，子实体经既有
 * 值对象构造与聚合守卫路径重建）。快照不承载卡片元素归属商品 ID
 * （版本行按商品维度定位，归属恒等于所属商品）——反序列化载体以占位
 * 值呈现（{@link #UNBOUND_PRODUCT_ID}），聚合装载路径以当前商品 ID
 * 重建归属。
 *
 * @author nona9961
 */
@Component
public class ProductSnapshotConvertor {

    /**
     * 卡片元素归属商品 ID 占位值（快照不承载归属；回滚路径由聚合
     * restoreContent 以当前商品 ID 重建归属——子实体归属恒等于所属商品）
     */
    private static final long UNBOUND_PRODUCT_ID = 0L;

    /**
     * 聚合 → 全量快照 JSON：主体字段 + 图片引用（ID/URL/主图标记）+
     * 自定义属性（ID/键值）+ 规格模板（维度 JSON 形态，未配置为 null）
     * + SKU 集（ID/组合摘要/价格/启用状态）平铺落 JSON。
     *
     * @param product 商品聚合（当前内容）
     * @return 全量快照 JSON
     */
    public String toSnapshotJson(Product product) {
        final ProductSnapshotJson snapshot = new ProductSnapshotJson(
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getBrandId(),
                product.imagesOrdered().stream()
                        .map(image -> new ProductSnapshotJson.SnapshotImage(
                                image.getId(), image.getUrl(), image.isPrimary()))
                        .toList(),
                product.attributesOrdered().stream()
                        .map(attribute -> new ProductSnapshotJson.SnapshotAttribute(
                                attribute.getId(), attribute.getKey(), attribute.getValue()))
                        .toList(),
                product.getSpecTemplate()
                        .map(template -> template.dimensionsOrdered().stream()
                                .map(dimension -> new SpecDimensionJson(
                                        dimension.getName(), dimension.valuesOrdered()))
                                .toList())
                        .orElse(null),
                product.skusOrdered().stream()
                        .map(sku -> new ProductSnapshotJson.SnapshotSku(
                                sku.getId(), sku.getSpecHash(), sku.getSpecSummary(),
                                sku.getPrice(), sku.isEnabled()))
                        .toList());
        return JacksonUtil.toJsonString(snapshot);
    }

    /**
     * 全量快照 JSON → 内容载体（回滚输入）：主体字段 + 图片引用 + 自定义
     * 属性 + 规格模板（null=历史未配置模板）+ SKU 集（身份/组合摘要/价格/
     * 启用状态恢复，卡片元素归属以占位值呈现）。
     *
     * @param snapshotJson 历史版本快照 JSON
     * @return 内容载体（含主体/图片/属性/规格模板/SKU 集）
     */
    public ProductContent toContent(String snapshotJson) {
        final ProductSnapshotJson snapshot =
                JacksonUtil.fromJsonString(snapshotJson, ProductSnapshotJson.class);
        if (snapshot == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本快照非法");
        }
        return new ProductContent(
                snapshot.name(),
                snapshot.description(),
                snapshot.categoryId(),
                snapshot.brandId(),
                snapshot.images().stream()
                        .map(image -> new ProductImage(image.id(), UNBOUND_PRODUCT_ID,
                                image.url(), image.primary()))
                        .toList(),
                snapshot.attributes().stream()
                        .map(attribute -> new ProductAttribute(attribute.id(), UNBOUND_PRODUCT_ID,
                                attribute.key(), attribute.value()))
                        .toList(),
                snapshot.specTemplate() == null
                        ? null
                        : new SpecTemplate(snapshot.specTemplate().stream()
                                .map(dimension -> new SpecItem(dimension.name(), dimension.values()))
                                .toList()),
                snapshot.skus().stream()
                        .map(sku -> new Sku(sku.id(), UNBOUND_PRODUCT_ID,
                                sku.specHash(), sku.specSummary(), sku.price(), sku.enabled()))
                        .toList());
    }
}