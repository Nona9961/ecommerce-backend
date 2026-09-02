package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品聚合根（catalog 域，草稿形态）：商品主体（SPU 内容：名称/描述/
 * 平台类目引用/品牌引用）+ 图片引用集合（product_image）+ 自定义属性
 * 键值对集合（product_attribute）。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无直接字段变更路径）：
 * <ol>
 *     <li>商品名称必填非空（草稿必填项，无名草稿拒绝保存）；</li>
 *     <li>同一商品至多一条主图（设置新主图自动清除原主图标记；删除主图后
 *         主图位清空）；</li>
 *     <li>图片 URL 必填非空（引用管理只存 URL 字符串，文件存储走 /files）；</li>
 *     <li>属性键必填非空；同一商品内属性键唯一（重复键无业务意义）；</li>
 *     <li>子实体 ID 在聚合内唯一（防御性，ID 由 Snowflake 生成）；</li>
 *     <li>创建即 DRAFT（草稿可保存不生效）——状态机与审核属后续阶段。</li>
 * </ol>
 * 引用语义：平台类目/品牌可空（草稿允许不完整）；引用目标的存在性与启用
 * 状态校验在用例层（引用查询能力由平台分类/品牌仓储提供），聚合不感知外部资源。
 *
 * @author nona9961
 */
public class Product {

    /**
     * 商品 ID（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 归属店铺 ID（tenant=shopId，创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品描述（可空）
     */
    private String description;

    /**
     * 平台类目 ID（可空引用）
     */
    private Long categoryId;

    /**
     * 品牌 ID（可空引用）
     */
    private Long brandId;

    /**
     * 商品状态（一期恒 DRAFT——草稿态）
     */
    private final ProductStatus status;

    /**
     * 图片引用集合（保持加入序）
     */
    private final List<ProductImage> images = new ArrayList<>();

    /**
     * 自定义属性集合（保持加入序）
     */
    private final List<ProductAttribute> attributes = new ArrayList<>();

    /**
     * 构造商品（仅 Factory 与仓储加载重建调用）：名称必填校验，
     * 其余主体字段可空（草稿允许不完整）。
     *
     * @param id          商品 ID
     * @param shopId      归属店铺 ID
     * @param name        商品名称（必填非空）
     * @param description 商品描述（可空）
     * @param categoryId  平台类目 ID（可空）
     * @param brandId     品牌 ID（可空）
     * @param status      商品状态
     */
    public Product(Long id, Long shopId, String name, String description,
                   Long categoryId, Long brandId, ProductStatus status) {
        this.id = id;
        this.shopId = shopId;
        this.name = requireName(name);
        this.description = description;
        this.categoryId = categoryId;
        this.brandId = brandId;
        this.status = status;
    }

    /**
     * 商品 ID。
     *
     * @return 商品 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 商品名称。
     *
     * @return 名称
     */
    public String getName() {
        return name;
    }

    /**
     * 商品描述。
     *
     * @return 描述；未填写为 null
     */
    public String getDescription() {
        return description;
    }

    /**
     * 平台类目 ID。
     *
     * @return 类目 ID；未挂载为 null
     */
    public Long getCategoryId() {
        return categoryId;
    }

    /**
     * 品牌 ID。
     *
     * @return 品牌 ID；未挂载为 null
     */
    public Long getBrandId() {
        return brandId;
    }

    /**
     * 商品状态。
     *
     * @return 状态
     */
    public ProductStatus getStatus() {
        return status;
    }

    /**
     * 图片引用集合快照（保持加入序，不可变副本）。
     *
     * @return 图片引用列表
     */
    public List<ProductImage> imagesOrdered() {
        return List.copyOf(images);
    }

    /**
     * 自定义属性集合快照（保持加入序，不可变副本）。
     *
     * @return 属性列表
     */
    public List<ProductAttribute> attributesOrdered() {
        return List.copyOf(attributes);
    }

    /**
     * 当前主图。
     *
     * @return 主图引用；未设置主图返回空
     */
    public Optional<ProductImage> primaryImage() {
        return images.stream().filter(ProductImage::isPrimary).findFirst();
    }

    /**
     * 更新商品主体：名称必填非空（不变量 1）；描述/类目/品牌可空
     * （引用目标存在性与启用状态校验在用例层）。
     *
     * @param name        新名称
     * @param description 新描述（可空）
     * @param categoryId  新平台类目 ID（可空）
     * @param brandId     新品牌 ID（可空）
     */
    public void updateInfo(String name, String description, Long categoryId, Long brandId) {
        this.name = requireName(name);
        this.description = description;
        this.categoryId = categoryId;
        this.brandId = brandId;
    }

    /**
     * 新增图片引用（不变量 2/3/5）：URL 必填非空；ID 在聚合内唯一；
     * 请求设为主图时自动清除原主图标记（至多一条主图）。
     *
     * @param image 图片引用（工厂创建，待入聚合）
     */
    public void addImage(ProductImage image) {
        if (image == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code(), "图片不能为空");
        }
        if (image.getUrl() == null || image.getUrl().isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code(), "图片URL不能为空");
        }
        if (getImageById(image.getId()).isPresent()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_DUPLICATE.code(), "图片已存在");
        }
        if (image.isPrimary()) {
            images.forEach(existing -> existing.markPrimary(false));
        }
        images.add(image);
    }

    /**
     * 删除图片引用（不变量 2）：目标必须存在；主图被删后主图位清空，
     * 其余图片标记不变。
     *
     * @param imageId 图片引用 ID
     */
    public void removeImage(Long imageId) {
        final ProductImage image = requireExistingImage(imageId);
        images.remove(image);
    }

    /**
     * 设置主图（不变量 2）：目标必须存在；清除原主图标记后设目标为主图。
     *
     * @param imageId 图片引用 ID
     */
    public void setPrimaryImage(Long imageId) {
        final ProductImage image = requireExistingImage(imageId);
        images.forEach(existing -> existing.markPrimary(false));
        image.markPrimary(true);
    }

    /**
     * 新增自定义属性（不变量 4/5）：键必填非空；键在聚合内唯一；ID 唯一。
     *
     * @param attribute 属性键值对（工厂创建，待入聚合）
     */
    public void addAttribute(ProductAttribute attribute) {
        if (attribute == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性不能为空");
        }
        if (attribute.getKey() == null || attribute.getKey().isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        if (getAttributeById(attribute.getId()).isPresent()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_DUPLICATE.code(), "属性已存在");
        }
        requireKeyFree(attribute.getKey(), null);
        attributes.add(attribute);
    }

    /**
     * 更新自定义属性（不变量 4）：目标必须存在；新键非空且与其余属性键保持
     * 唯一（排除自身，改回自身键合法）；值可空。
     *
     * @param attributeId 属性 ID
     * @param key         新键
     * @param value       新值（可空）
     */
    public void updateAttribute(Long attributeId, String key, String value) {
        final ProductAttribute attribute = requireExistingAttribute(attributeId);
        if (key == null || key.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        requireKeyFree(key, attributeId);
        attribute.update(key, value);
    }

    /**
     * 删除自定义属性：目标必须存在。
     *
     * @param attributeId 属性 ID
     */
    public void removeAttribute(Long attributeId) {
        final ProductAttribute attribute = requireExistingAttribute(attributeId);
        attributes.remove(attribute);
    }

    /**
     * 按 ID 取图片引用。
     *
     * @param imageId 图片引用 ID
     * @return 图片引用；不存在返回空
     */
    public Optional<ProductImage> getImageById(Long imageId) {
        return images.stream().filter(image -> image.getId().equals(imageId)).findFirst();
    }

    /**
     * 按 ID 取自定义属性。
     *
     * @param attributeId 属性 ID
     * @return 属性；不存在返回空
     */
    public Optional<ProductAttribute> getAttributeById(Long attributeId) {
        return attributes.stream().filter(attribute -> attribute.getId().equals(attributeId)).findFirst();
    }

    /**
     * 按 ID 取图片引用并断言存在（图片编辑/删除的目标必须属于本商品；
     * 不属于本商品即呈现为不存在，不泄露归属信息）。
     *
     * @param imageId 图片引用 ID
     * @return 图片引用
     */
    private ProductImage requireExistingImage(Long imageId) {
        return getImageById(imageId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_NOT_FOUND.code(), "图片不存在"));
    }

    /**
     * 按 ID 取属性并断言存在（属性编辑/删除的目标必须属于本商品；
     * 不属于本商品即呈现为不存在，不泄露归属信息）。
     *
     * @param attributeId 属性 ID
     * @return 属性
     */
    private ProductAttribute requireExistingAttribute(Long attributeId) {
        return getAttributeById(attributeId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_NOT_FOUND.code(), "属性不存在"));
    }

    /**
     * 键唯一性守卫：目标键已被其余属性占用即拒绝（更新场景排除自身）。
     *
     * @param key        目标键
     * @param excludeId 排除的属性 ID（更新场景排除自身；新增传 null）
     */
    private void requireKeyFree(String key, Long excludeId) {
        final boolean occupied = attributes.stream()
                .anyMatch(attribute -> attribute.getKey().equals(key)
                        && (excludeId == null || !attribute.getId().equals(excludeId)));
        if (occupied) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_DUPLICATE.code(), "属性键已存在");
        }
    }

    /**
     * 名称必填校验（构造与编辑共用）。
     *
     * @param name 商品名称
     * @return 校验通过的名称
     */
    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NAME_BLANK.code(), "商品名称不能为空");
        }
        return name;
    }
}