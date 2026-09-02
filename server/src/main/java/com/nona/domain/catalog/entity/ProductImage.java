package com.nona.domain.catalog.entity;

/**
 * 商品图片引用（Product 聚合内实体，对应 product_image 表行）：
 * 商品画廊中的一张图片（URL 引用 + 主图标记）。
 * <p>
 * URL 为统一形态 {@code /files/{objectKey}}（上传端点产出，业务表只存
 * 该字符串）；primary 标记主图——「同一商品至多一条主图」不变量收敛在
 * {@link Product} 聚合方法内（addImage/setPrimaryImage/removeImage），
 * 本实体不提供对外变更路径（markPrimary 仅聚合调用）。
 *
 * @author nona9961
 */
public class ProductImage {

    /**
     * 图片引用 ID（Snowflake）
     */
    private final Long id;

    /**
     * 归属商品 ID（rootId 关联，创建时定型不可变）
     */
    private final Long productId;

    /**
     * 图片 URL（/files/{objectKey} 形态）
     */
    private final String url;

    /**
     * 是否主图（同一商品至多一条为 true）
     */
    private boolean primary;

    /**
     * 构造图片引用。
     *
     * @param id        图片引用 ID
     * @param productId 归属商品 ID
     * @param url       图片 URL
     * @param primary   是否主图
     */
    public ProductImage(Long id, Long productId, String url, boolean primary) {
        this.id = id;
        this.productId = productId;
        this.url = url;
        this.primary = primary;
    }

    /**
     * 图片引用 ID。
     *
     * @return 图片引用 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * 图片 URL。
     *
     * @return URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 是否主图。
     *
     * @return 主图返回 true
     */
    public boolean isPrimary() {
        return primary;
    }

    /**
     * 设置/清除主图标记（仅 {@link Product} 聚合的图片管理路径调用；
     * 「至多一条主图」由聚合方法统一编排）。
     *
     * @param primary 是否主图
     */
    void markPrimary(boolean primary) {
        this.primary = primary;
    }
}