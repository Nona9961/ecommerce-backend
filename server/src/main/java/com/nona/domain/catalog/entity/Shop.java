package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 店铺聚合根（catalog 域）：店铺信息（名称/logo/简介）+ 店铺状态 + 店铺分类集合。
 * <p>
 * 店铺是租户（tenantID=shopId）的来源与锚点：商家在售商品、运费模板、店铺分类
 * 等数据均归属店铺租户；店铺聚合用于承载店铺信息编辑与店铺分类 CRUD。分类集合为平铺结构（店铺分类无层级概念），order 为展示排序。
 * <p>
 * 关键不变量：店铺名称非空；分类名称非空；分类 ID 在聚合内唯一；新增分类排序
 * 取当前最大 order + 1（空集合从 1 起）；删除分类不重排其余 order。
 * 不变量全部收敛在本聚合方法内，包外无直接字段变更路径。
 *
 * @author nona9961
 */
public class Shop {

    /**
     * 店铺 ID（Snowflake，聚合根标识；同时是租户锚点 shopId）
     */
    private final Long id;

    /**
     * 店铺名称
     */
    private String name;

    /**
     * 店铺 logo（可空）
     */
    private String logo;

    /**
     * 店铺简介（可空）
     */
    private String description;

    /**
     * 店铺状态（一期仅建状态位，冻结联动后续阶段）
     */
    private ShopStatus status;

    /**
     * 店铺分类集合（保持加载/新增顺序；展示顺序以 order 排序为准）
     */
    private final List<ShopCategory> categories = new ArrayList<>();

    /**
     * 构造店铺（仅 Factory 与仓储加载重建调用）。
     *
     * @param id          店铺 ID
     * @param name        店铺名称
     * @param logo        店铺 logo
     * @param description 店铺简介
     * @param status      店铺状态
     */
    public Shop(Long id, String name, String logo, String description, ShopStatus status) {
        this.id = id;
        this.name = name;
        this.logo = logo;
        this.description = description;
        this.status = status;
    }

    /**
     * 店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 店铺名称。
     *
     * @return 店铺名称
     */
    public String getName() {
        return name;
    }

    /**
     * 店铺 logo。
     *
     * @return logo；未设置返回 null
     */
    public String getLogo() {
        return logo;
    }

    /**
     * 店铺简介。
     *
     * @return 简介；未设置返回 null
     */
    public String getDescription() {
        return description;
    }

    /**
     * 店铺状态。
     *
     * @return 店铺状态
     */
    public ShopStatus getStatus() {
        return status;
    }

    /**
     * 编辑店铺信息：名称必填非空（空名称拒绝），logo/简介可空；
     * 店铺状态不接受商家编辑（状态由平台侧管理，编辑不影响状态）。
     *
     * @param name        新店铺名称
     * @param logo        新 logo（可空）
     * @param description 新简介（可空）
     */
    public void updateInfo(String name, String logo, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_NAME_BLANK.code(), "店铺名称不能为空");
        }
        this.name = name;
        this.logo = logo;
        this.description = description;
    }

    /**
     * 新增店铺分类：名称非空；ID 在聚合内唯一；排序自动分配
     * （当前最大 order + 1，空集合从 1 起）。
     *
     * @param category 店铺分类（ID 由 Factory 生成）
     */
    public void addCategory(ShopCategory category) {
        if (category == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NAME_BLANK.code(), "分类不能为空");
        }
        if (category.getName() == null || category.getName().isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NAME_BLANK.code(), "分类名称不能为空");
        }
        if (getCategoryById(category.getId()).isPresent()) {
            BusinessAssert.throwBusinessWithCode(
                    EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_DUPLICATE.code(),
                    "分类已存在：{}", category.getId());
        }
        if (category.getOrder() <= 0) {
            category.assignOrder(nextOrder());
        }
        categories.add(category);
    }

    /**
     * 分类改名：目标必须存在；新名称非空；排序保持不变。
     *
     * @param categoryId 目标分类 ID
     * @param newName    新名称
     */
    public void renameCategory(Long categoryId, String newName) {
        final ShopCategory category = requireExisting(categoryId);
        if (newName == null || newName.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NAME_BLANK.code(), "分类名称不能为空");
        }
        category.rename(newName);
    }

    /**
     * 删除分类：目标必须存在；删除后其余分类排序保持（不重排）。
     *
     * @param categoryId 目标分类 ID
     */
    public void removeCategory(Long categoryId) {
        final ShopCategory category = requireExisting(categoryId);
        categories.remove(category);
    }

    /**
     * 分类条数。
     *
     * @return 条数
     */
    public int categoryCount() {
        return categories.size();
    }

    /**
     * 分类按排序升序的快照（不可变副本，读路径使用）。
     *
     * @return 排序后的分类快照
     */
    public List<ShopCategory> categoriesOrdered() {
        return categories.stream()
                .sorted(Comparator.comparingInt(ShopCategory::getOrder))
                .toList();
    }

    /**
     * 按 ID 取分类。
     *
     * @param categoryId 分类 ID
     * @return 分类；不存在返回空
     */
    public Optional<ShopCategory> getCategoryById(Long categoryId) {
        return categories.stream().filter(category -> category.getId().equals(categoryId)).findFirst();
    }

    /**
     * 计算下一个排序值：当前最大 order + 1（空集合返回 1）。
     *
     * @return 下一个排序值
     */
    private int nextOrder() {
        return categories.stream()
                .mapToInt(ShopCategory::getOrder)
                .max()
                .orElse(0) + 1;
    }

    /**
     * 按 ID 取分类并断言存在（编辑/删除的目标必须属于本店；
     * 不属于本店即呈现为不存在，不泄露归属信息）。
     *
     * @param categoryId 分类 ID
     * @return 分类
     */
    private ShopCategory requireExisting(Long categoryId) {
        return getCategoryById(categoryId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NOT_FOUND.code(), "分类不存在"));
    }
}