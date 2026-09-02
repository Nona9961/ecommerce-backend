package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Product;
import com.nona.inf.persistence.po.catalog.ProductPO;
import org.springframework.stereotype.Component;

/**
 * 商品聚合根 ↔ 商品 PO 转换器（主表 product + 从表 product_image /
 * product_attribute 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 商品 ID，归属列 shop_id 承载店铺）；从表行经
 * {@link ProductImageConvertor} / {@link ProductAttributeConvertor} 逐行
 * 转换后由聚合新增方法装载（addImage/addAttribute 保持加入序与持久化
 * 主图标记，聚合内不变量在装载路径同样生效——持久化数据已保证不变量，
 * 装载校验为防御性兜底）。other 参数为两个从表行集合（读路径由仓储
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
     * 构造商品转换器。
     *
     * @param imageConvertor      图片行转换器
     * @param attributeConvertor  属性行转换器
     */
    public ProductConvertor(ProductImageConvertor imageConvertor,
                            ProductAttributeConvertor attributeConvertor) {
        this.imageConvertor = imageConvertor;
        this.attributeConvertor = attributeConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主表行承载聚合根身份（id = 商品 ID）与草稿主体字段/状态，
     * 审计时间戳与租户列由持久化层填充。
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
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经子转换器转换并装载（图片先于属性装载，各保持加入序）。
     */
    @Override
    protected Product safedConvertToRoot(ProductPO po, ProductChildPos childPos) {
        final Product product = new Product(po.getId(), po.getShopId(), po.getName(),
                po.getDescription(), po.getCategoryId(), po.getBrandId(), po.getStatus());
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
}