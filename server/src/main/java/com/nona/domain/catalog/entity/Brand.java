package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

/**
 * 品牌聚合根（catalog 域）：平台品牌库，商家商品可挂品牌。
 * <p>
 * 承载：品牌名称（全局唯一——数据库唯一约束 + 用例守卫）、
 * logo（可空）、状态（ENABLED/DISABLED）。
 * <p>
 * 关键不变量：名称非空；名称全局唯一。不变量收敛在本聚合方法内
 * （rename 守卫）与创建路径（工厂守卫），包外无直接字段变更路径。
 * 「删除」= 禁用（disable-not-delete 软删）：禁用后既有商品保持可见、
 * 新商品不可挂载（挂载校验在商品创建/更新路径），行不删除。
 *
 * @author nona9961
 */
public class Brand {

    /**
     * 品牌 ID（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 品牌名称（全局唯一）
     */
    private String name;

    /**
     * 品牌 logo URL（可空）
     */
    private String logo;

    /**
     * 品牌状态
     */
    private BrandStatus status;

    /**
     * 构造品牌（仅 Factory 与仓储加载重建调用；创建路径校验见工厂）。
     *
     * @param id     品牌 ID
     * @param name   品牌名称
     * @param logo   品牌 logo（可空）
     * @param status 品牌状态
     */
    public Brand(Long id, String name, String logo, BrandStatus status) {
        this.id = id;
        this.name = name;
        this.logo = logo;
        this.status = status;
    }

    /**
     * 品牌 ID。
     *
     * @return 品牌 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 品牌名称。
     *
     * @return 品牌名称
     */
    public String getName() {
        return name;
    }

    /**
     * 品牌 logo。
     *
     * @return logo；未设置返回 null
     */
    public String getLogo() {
        return logo;
    }

    /**
     * 品牌状态。
     *
     * @return 品牌状态
     */
    public BrandStatus getStatus() {
        return status;
    }

    /**
     * 品牌改名：新名称必填非空（空名称拒绝）。
     *
     * @param newName 新品牌名称
     */
    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NAME_BLANK.code(), "品牌名称不能为空");
        }
        this.name = newName;
    }

    /**
     * 更新品牌 logo（可空：传 null 清除）。
     *
     * @param newLogo 新 logo URL；null 清除
     */
    public void updateLogo(String newLogo) {
        this.logo = newLogo;
    }

    /**
     * 禁用品牌（「删除」= 禁用软删）：状态迁移 DISABLED，幂等。
     */
    public void disable() {
        this.status = BrandStatus.DISABLED;
    }

    /**
     * 启用品牌（禁用态恢复）：状态迁移 ENABLED，幂等。
     */
    public void enable() {
        this.status = BrandStatus.ENABLED;
    }
}