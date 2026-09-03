package com.nona.inf.persistence.converters;

import com.nona.inf.persistence.po.catalog.ProductAttributePO;
import com.nona.inf.persistence.po.catalog.ProductImagePO;
import com.nona.inf.persistence.po.catalog.SkuPO;

import java.util.List;

/**
 * 商品聚合 from-表行集合装配载体（DifferRepository other 泛型参数）。
 * <p>
 * 商品聚合含三个从表集合（图片 product_image / 属性 product_attribute /
 * SKU product_sku），而 {@link RdbGeneralConvertor} 的 other 参数形态
 * 单一——本载体将三个集合打包传递（读路径由仓储 {@code getOther} 装配、
 * 转换器拆包装载）。
 *
 * @param images     图片引用行（按加入序）
 * @param attributes 属性行（按加入序）
 * @param skus       SKU 行（按模板展开序）
 */
public record ProductChildPos(
        List<ProductImagePO> images,
        List<ProductAttributePO> attributes,
        List<SkuPO> skus
) {
}