package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 商品工厂：商品聚合根的创建入口（ID 生成 + 归属定型 + 初始状态定型）。
 * <p>
 * 创建校验：归属店铺必填非空；商品名称必填非空（草稿必填项）；图片 URL
 * 必填非空；属性键必填非空。商品初始状态恒为 DRAFT（草稿可保存不生效，
 * 状态机迁移属后续阶段）；子实体创建绑定归属商品 ID（rootId 关联，
 * 创建时定型不可变），键唯一性/主图唯一性校验在聚合新增方法内。
 *
 * @author nona9961
 */
@Component
public class ProductFactory {

    /**
     * 创建商品草稿：生成 Snowflake ID、初始状态 DRAFT。
     *
     * @param shopId      归属店铺 ID（必填非空）
     * @param name        商品名称（必填非空）
     * @param description 商品描述（可空）
     * @param categoryId  平台类目 ID（可空引用）
     * @param brandId     品牌 ID（可空引用）
     * @return 新建草稿（初始无图片/属性）
     */
    public Product createDraft(Long shopId, String name, String description,
                               Long categoryId, Long brandId) {
        if (shopId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_SHOP_REQUIRED.code(), "店铺不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NAME_BLANK.code(), "商品名称不能为空");
        }
        return new Product(IDUtils.generateID(), shopId, name, description,
                categoryId, brandId, ProductStatus.DRAFT);
    }

    /**
     * 创建图片引用：生成独立 Snowflake ID 并绑定归属商品
     * （主图唯一性校验在聚合新增路径）。
     *
     * @param product 归属商品（必填非空）
     * @param url     图片 URL（必填非空；/files/{objectKey} 形态）
     * @param primary 是否主图
     * @return 新建图片引用（待加入商品聚合）
     */
    public ProductImage createImage(Product product, String url, boolean primary) {
        if (product == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_REQUIRED.code(), "商品不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code(), "图片URL不能为空");
        }
        return new ProductImage(IDUtils.generateID(), product.getId(), url, primary);
    }

    /**
     * 创建自定义属性：生成独立 Snowflake ID 并绑定归属商品
     * （键唯一性校验在聚合新增路径）。
     *
     * @param product 归属商品（必填非空）
     * @param key     属性键（必填非空）
     * @param value   属性值（可空）
     * @return 新建属性（待加入商品聚合）
     */
    public ProductAttribute createAttribute(Product product, String key, String value) {
        if (product == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_REQUIRED.code(), "商品不能为空");
        }
        if (key == null || key.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        return new ProductAttribute(IDUtils.generateID(), product.getId(), key, value);
    }
}