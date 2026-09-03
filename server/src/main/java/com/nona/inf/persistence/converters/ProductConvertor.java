package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Product;
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
     * 构造商品转换器。
     *
     * @param imageConvertor     图片行转换器
     * @param attributeConvertor 属性行转换器
     * @param skuConvertor       SKU 行转换器
     */
    public ProductConvertor(ProductImageConvertor imageConvertor,
                            ProductAttributeConvertor attributeConvertor,
                            SkuConvertor skuConvertor) {
        this.imageConvertor = imageConvertor;
        this.attributeConvertor = attributeConvertor;
        this.skuConvertor = skuConvertor;
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
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经子转换器转换并装载（图片先于属性装载，各保持加入序；
     * SKU 集经 V2 构造随模板一并装入）。
     */
    @Override
    protected Product safedConvertToRoot(ProductPO po, ProductChildPos childPos) {
        final Product product = new Product(po.getId(), po.getShopId(), po.getName(),
                po.getDescription(), po.getCategoryId(), po.getBrandId(), po.getStatus(),
                fromSpecTemplateJson(po.getSpecTemplateJson()), toSkus(childPos));
        if (childPos != null) {
            for (final var imagePo : childPos.images()) {
                product.addImage(imageConvertor.toDomain(imagePo));
            }
            for (final var attributePo : childPos.attributes()) {
                product.addAttribute(attributeConvertor.toDomain(attributePo));
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