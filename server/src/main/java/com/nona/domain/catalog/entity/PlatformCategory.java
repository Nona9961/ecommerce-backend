package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

/**
 * 平台分类聚合根（catalog 域）：平台一级分类，平台货架组织单位，
 * 与商家店铺分类（Shop 聚合内 ShopCategory）相互独立（两套体系无关联逻辑）。
 * <p>
 * 承载：分类名称（全局唯一，含禁用态不可复用——数据库唯一约束 + 用例守卫）、
 * 展示排序（正数）、状态（ENABLED/DISABLED）。
 * <p>
 * 关键不变量：名称非空；排序为正数。不变量收敛在本聚合方法内
 * （rename/reorder 守卫）与创建路径（工厂守卫），包外无直接字段变更路径。
 * 「删除」= 禁用（disable-not-delete 软删）：disable 后既有商品保持历史
 * 挂载可见、新商品不可挂载（挂载校验在商品创建/更新路径），行不删除。
 *
 * @author nona9961
 */
public class PlatformCategory {

    /**
     * 分类 ID（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 分类名称（全局唯一）
     */
    private String name;

    /**
     * 展示排序（正数；列表按此升序）
     */
    private int order;

    /**
     * 分类状态
     */
    private CategoryStatus status;

    /**
     * 构造平台分类（仅 Factory 与仓储加载重建调用；创建路径校验见工厂）。
     *
     * @param id     分类 ID
     * @param name   分类名称
     * @param order  展示排序
     * @param status 分类状态
     */
    public PlatformCategory(Long id, String name, int order, CategoryStatus status) {
        this.id = id;
        this.name = name;
        this.order = order;
        this.status = status;
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
     * @return 排序值
     */
    public int getOrder() {
        return order;
    }

    /**
     * 分类状态。
     *
     * @return 分类状态
     */
    public CategoryStatus getStatus() {
        return status;
    }

    /**
     * 分类改名：新名称必填非空（空名称拒绝）。
     *
     * @param newName 新分类名称
     */
    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NAME_BLANK.code(), "分类名称不能为空");
        }
        this.name = newName;
    }

    /**
     * 调整展示排序：新排序必须为正数。
     *
     * @param newOrder 新排序值
     */
    public void reorder(int newOrder) {
        if (newOrder <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_ORDER_INVALID.code(), "分类排序必须为正数");
        }
        this.order = newOrder;
    }

    /**
     * 禁用分类（「删除」= 禁用软删）：状态迁移 DISABLED，幂等。
     */
    public void disable() {
        this.status = CategoryStatus.DISABLED;
    }

    /**
     * 启用分类（禁用态恢复）：状态迁移 ENABLED，幂等。
     */
    public void enable() {
        this.status = CategoryStatus.ENABLED;
    }
}