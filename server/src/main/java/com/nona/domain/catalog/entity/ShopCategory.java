package com.nona.domain.catalog.entity;

/**
 * 店铺分类（Shop 聚合内实体，对应 shop_category 表行）：
 * 商家维度的店内商品归类，一个商品可归属多个店铺分类（多对多，商品侧承载）。
 * <p>
 * 携带归属键 shopId（所属店铺 ID，创建时定型不可变）——持久化行级映射所需；
 * order 为展示排序（聚合内分配：新增取当前最大 order + 1，删除不重排），
 * 排序值的分配与保持不变量全部收敛在 {@link Shop} 聚合方法内
 * （addCategory/removeCategory），本实体不提供对外变更路径。
 *
 * @author nona9961
 */
public class ShopCategory {

    /**
     * 分类 ID（Snowflake）
     */
    private final Long id;

    /**
     * 所属店铺 ID（rootId 关联，与所属店铺一致）
     */
    private final Long shopId;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 展示排序（聚合内分配；新建未入聚合时为 0 占位）
     */
    private int order;

    /**
     * 构造店铺分类（新建场景：order 未分配，入聚合时由 Shop 分配）。
     *
     * @param id     分类 ID
     * @param shopId 所属店铺 ID
     * @param name   分类名称
     */
    public ShopCategory(Long id, Long shopId, String name) {
        this(id, shopId, name, 0);
    }

    /**
     * 构造店铺分类（加载重建场景：order 为持久化值，原样恢复不重排）。
     *
     * @param id     分类 ID
     * @param shopId 所属店铺 ID
     * @param name   分类名称
     * @param order  展示排序（持久化值）
     */
    public ShopCategory(Long id, Long shopId, String name, int order) {
        this.id = id;
        this.shopId = shopId;
        this.name = name;
        this.order = order;
    }

    /**
     * 分类 ID。
     *
     * @return 分类 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 所属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 分类名称。
     *
     * @return 分类名称
     */
    public String getName() {
        return name;
    }

    /**
     * 展示排序。
     *
     * @return 排序值；新建未入聚合时为 0
     */
    public int getOrder() {
        return order;
    }

    /**
     * 改名（编辑路径唯一入口，由 {@link Shop#renameCategory} 校验后调用）。
     *
     * @param newName 新名称（非空校验在聚合侧）
     */
    public void rename(String newName) {
        this.name = newName;
    }

    /**
     * 分配展示排序（仅 {@link Shop} 聚合新增路径调用；外部不得直接变更）。
     *
     * @param order 排序值
     */
    void assignOrder(int order) {
        this.order = order;
    }
}