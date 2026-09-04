package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductContent;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.util.JacksonUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品聚合根 ↔ 商品 PO 转换器（主表 product + 从表 product_image /
 * product_attribute / product_sku 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 商品 ID，归属列 shop_id 承载店铺；规格模板
 * 以 spec_template_json 列承载——值对象无独立表）；从表行经
 * {@link ProductImageConvertor} / {@link ProductAttributeConvertor} /
 * {@link SkuConvertor} 逐行转换后由聚合构造/新增方法装载（图片/属性
 * 保持加入序与持久化主图标记，聚合内不变量在装载路径同样生效——持久化
 * 数据已保证不变量，装载校验为防御性兜底；SKU 集经 V2 构造直接装入，
 * 装载路径信任持久化数据）。other 参数为三个从表行集合（读路径由仓储
 * getOther 提供，经租户过滤仅含本店铺商品行）。
 *
 * @author nona9961
 */
@Component
public class ProductConvertor extends AbstractConvertor<Product, ProductPO, ProductChildPos> {

    /**
     * 待审草稿子实体归属商品 ID 占位值（待审草稿 JSON 不承载卡片元素
     * 归属；审批覆盖路径由聚合 restoreContent 以当前商品 ID 重建归属——
     * 子实体归属恒等于所属商品）
     */
    private static final long UNBOUND_PRODUCT_ID = 0L;

    /**
     * 图片行转换器
     */
    private final ProductImageConvertor imageConvertor;

    /**
     * 属性行转换器
     */
    private final ProductAttributeConvertor attributeConvertor;

    /**
     * SKU 行转换器
     */
    private final SkuConvertor skuConvertor;

    /**
     * 店铺分类绑定行转换器
     */
    private final ProductShopCategoryRelConvertor shopCategoryRefConvertor;

    /**
     * 构造商品转换器。
     *
     * @param imageConvertor         图片行转换器
     * @param attributeConvertor     属性行转换器
     * @param skuConvertor           SKU 行转换器
     * @param shopCategoryRefConvertor 店铺分类绑定行转换器
     */
    public ProductConvertor(ProductImageConvertor imageConvertor,
                            ProductAttributeConvertor attributeConvertor,
                            SkuConvertor skuConvertor,
                            ProductShopCategoryRelConvertor shopCategoryRefConvertor) {
        this.imageConvertor = imageConvertor;
        this.attributeConvertor = attributeConvertor;
        this.skuConvertor = skuConvertor;
        this.shopCategoryRefConvertor = shopCategoryRefConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主表行承载聚合根身份（id = 商品 ID）与草稿主体字段/状态/规格模板
     * JSON，审计时间戳与租户列由持久化层填充。
     */
    @Override
    protected ProductPO safedConvertToPO(Product root) {
        final ProductPO po = new ProductPO();
        po.setId(root.getId());
        po.setShopId(root.getShopId());
        po.setName(root.getName());
        po.setDescription(root.getDescription());
        po.setCategoryId(root.getCategoryId());
        po.setBrandId(root.getBrandId());
        po.setStatus(root.getStatus());
        po.setSpecTemplateJson(toSpecTemplateJson(root.getSpecTemplate().orElse(null)));
        po.setPendingDraftJson(toPendingDraftJson(root.getPendingContent().orElse(null)));
        po.setFreightTemplateId(root.getFreightTemplateId());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经子转换器转换并装载（图片先于属性装载，各保持加入序；
     * SKU 集经 V2 构造随模板一并装入）；待审草稿位（pending_draft_json
     * 列）读回经聚合装载路径接线（持久化读回：信任列数据，直接装载）。
     */
    @Override
    protected Product safedConvertToRoot(ProductPO po, ProductChildPos childPos) {
        final Product product = new Product(po.getId(), po.getShopId(), po.getName(),
                po.getDescription(), po.getCategoryId(), po.getBrandId(), po.getStatus(),
                fromSpecTemplateJson(po.getSpecTemplateJson()), toSkus(childPos));
        product.restorePendingContent(fromPendingDraftJson(po.getPendingDraftJson()));
        product.restoreFreightTemplateId(po.getFreightTemplateId());
        if (childPos != null) {
            for (final var imagePo : childPos.images()) {
                product.restoreImage(imageConvertor.toDomain(imagePo));
            }
            for (final var attributePo : childPos.attributes()) {
                product.restoreAttribute(attributeConvertor.toDomain(attributePo));
            }
            for (final var refPo : childPos.shopCategoryRefs()) {
                product.restoreShopCategoryRef(shopCategoryRefConvertor.toDomain(refPo));
            }
        }
        return product;
    }

    /**
     * 规格模板 → JSON 列内容（null 模板→null 列；空模板→空数组形态）。
     *
     * @param template 规格模板；null=尚未配置
     * @return JSON 字符串；未配置返回 null
     */
    private static String toSpecTemplateJson(SpecTemplate template) {
        if (template == null) {
            return null;
        }
        return JacksonUtil.toJsonString(new SpecTemplateJson(
                template.dimensionsOrdered().stream()
                        .map(dimension -> new SpecDimensionJson(
                                dimension.getName(), dimension.valuesOrdered()))
                        .toList()));
    }

    /**
     * JSON 列内容 → 规格模板（构造路径重建，结构校验由值对象承载）。
     *
     * @param json JSON 字符串；null/空白=未配置模板
     * @return 规格模板；未配置返回 null
     */
    private static SpecTemplate fromSpecTemplateJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        final SpecTemplateJson dto = JacksonUtil.fromJsonString(json, SpecTemplateJson.class);
        if (dto == null) {
            return null;
        }
        return new SpecTemplate(dto.dimensions().stream()
                .map(dimension -> new SpecItem(dimension.name(), dimension.values()))
                .toList());
    }

    /**
     * 待审草稿内容 → JSON 列内容（null=无待审草稿）：序列化形态与版本
     * 快照一致（复用 {@link ProductSnapshotJson} 中间形态——待审草稿内容
     * 与版本快照同为「全量内容」语义）。
     *
     * @param content 待审草稿内容；null=无待审草稿
     * @return JSON 字符串；无待审草稿返回 null
     */
    private static String toPendingDraftJson(ProductContent content) {
        if (content == null) {
            return null;
        }
        final ProductSnapshotJson snapshot = new ProductSnapshotJson(
                content.name(),
                content.description(),
                content.categoryId(),
                content.brandId(),
                content.images().stream()
                        .map(image -> new ProductSnapshotJson.SnapshotImage(
                                image.getId(), image.getUrl(), image.isPrimary()))
                        .toList(),
                content.attributes().stream()
                        .map(attribute -> new ProductSnapshotJson.SnapshotAttribute(
                                attribute.getId(), attribute.getKey(), attribute.getValue()))
                        .toList(),
                content.specTemplate() == null ? null : content.specTemplate().dimensionsOrdered().stream()
                        .map(dimension -> new SpecDimensionJson(
                                dimension.getName(), dimension.valuesOrdered()))
                        .toList(),
                content.skus().stream()
                        .map(sku -> new ProductSnapshotJson.SnapshotSku(
                                sku.getId(), sku.getSpecHash(), sku.getSpecSummary(),
                                sku.getPrice(), sku.isEnabled()))
                        .toList());
        return JacksonUtil.toJsonString(snapshot);
    }

    /**
     * JSON 列内容 → 待审草稿内容（持久化读回；null/空白 = 无待审草稿）。
     * 序列化形态与版本快照一致（复用 {@link ProductSnapshotJson} 中间
     * 形态），反序列化路径与版本快照读回同构（子实体归属以当前商品 ID
     * 由聚合装载路径重建）。
     *
     * @param json 待审草稿 JSON 列内容；null/空白=无待审草稿
     * @return 待审草稿内容；无待审草稿返回 null
     */
    private static ProductContent fromPendingDraftJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        final ProductSnapshotJson snapshot = JacksonUtil.fromJsonString(json, ProductSnapshotJson.class);
        if (snapshot == null) {
            return null;
        }
        return new ProductContent(
                snapshot.name(),
                snapshot.description(),
                snapshot.categoryId(),
                snapshot.brandId(),
                snapshot.images().stream()
                        .map(image -> new ProductImage(image.id(), UNBOUND_PRODUCT_ID, image.url(), image.primary()))
                        .toList(),
                snapshot.attributes().stream()
                        .map(attribute -> new ProductAttribute(
                                attribute.id(), UNBOUND_PRODUCT_ID, attribute.key(), attribute.value()))
                        .toList(),
                snapshot.specTemplate() == null
                        ? null
                        : new SpecTemplate(snapshot.specTemplate().stream()
                                .map(dimension -> new SpecItem(dimension.name(), dimension.values()))
                                .toList()),
                snapshot.skus().stream()
                        .map(sku -> new Sku(sku.id(), UNBOUND_PRODUCT_ID, sku.specHash(), sku.specSummary(),
                                sku.price(), sku.enabled()))
                        .toList());
    }

    /**
     * 从表行集合 → 领域 SKU 列表（拆包装载：V2 构造的 skus 参数）。
     *
     * @param childPos 从表行集合载体；null 视为无从表行
     * @return SKU 列表
     */
    private List<Sku> toSkus(ProductChildPos childPos) {
        if (childPos == null || childPos.skus() == null) {
            return List.of();
        }
        return childPos.skus().stream().map(skuConvertor::toDomain).toList();
    }
}