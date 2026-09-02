package com.nona.domain.catalog.entity;

/**
 * 商品自定义属性键值对（Product 聚合内实体，对应 product_attribute 表行）：
 * 商家自定义的额外描述属性（键值对形态）。
 * <p>
 * 键必填；「同一商品内键唯一」不变量收敛在 {@link Product} 聚合方法内
 * （addAttribute/updateAttribute），本实体不提供对外变更路径
 * （update 仅聚合调用）。值可空。键的长度上限由持久化列约束承载。
 *
 * @author nona9961
 */
public class ProductAttribute {

    /**
     * 属性 ID（Snowflake）
     */
    private final Long id;

    /**
     * 归属商品 ID（rootId 关联，创建时定型不可变）
     */
    private final Long productId;

    /**
     * 属性键（同一商品内唯一）
     */
    private String key;

    /**
     * 属性值（可空）
     */
    private String value;

    /**
     * 构造属性键值对。
     *
     * @param id        属性 ID
     * @param productId 归属商品 ID
     * @param key       属性键
     * @param value     属性值（可空）
     */
    public ProductAttribute(Long id, Long productId, String key, String value) {
        this.id = id;
        this.productId = productId;
        this.key = key;
        this.value = value;
    }

    /**
     * 属性 ID。
     *
     * @return 属性 ID
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
     * 属性键。
     *
     * @return 属性键
     */
    public String getKey() {
        return key;
    }

    /**
     * 属性值。
     *
     * @return 属性值；未填写为 null
     */
    public String getValue() {
        return value;
    }

    /**
     * 更新键值（仅 {@link Product} 聚合的属性编辑路径调用——键唯一性与
     * 非空校验由聚合方法统一编排）。
     *
     * @param key   新键
     * @param value 新值（可空）
     */
    void update(String key, String value) {
        this.key = key;
        this.value = value;
    }
}