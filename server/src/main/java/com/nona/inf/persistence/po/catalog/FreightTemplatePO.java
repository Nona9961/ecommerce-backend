package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 运费模板持久化对象（freight_template 表，tenant=shopId）：FreightTemplate
 * 聚合根主表（单表单行，无集合子实体）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离——
 * 跨店铺访问模板行在 Hibernate 租户过滤层即被拦截。shop_id 为业务关联列
 * （rootId 关联 shop 主表，冗余承载归属便于店铺维度查询）；计费参数按
 * 规则类型解释（FREE 三者皆空 / PER_ITEM 仅单价 / THRESHOLD_FREE 仅
 * 基础运费与阈值），金额单位为分。
 *
 * @author nona9961
 */
@Entity
@Table(name = "freight_template", indexes = {
        @Index(name = "idx_freight_template_shop", columnList = "shop_id")
})
public class FreightTemplatePO extends TenantScopedBasePO {

    /**
     * 所属店铺 ID（rootId 关联）
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 模板名称
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 运费规则类型（FREE / PER_ITEM / THRESHOLD_FREE）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, name = "rule_type")
    private FreightRuleType ruleType;

    /**
     * 按件单价（分；仅 PER_ITEM 使用）
     */
    @Column(name = "per_item_price")
    private Long perItemPrice;

    /**
     * 基础运费（分；仅 THRESHOLD_FREE 使用）
     */
    @Column(name = "base_freight")
    private Long baseFreight;

    /**
     * 免邮阈值（分；仅 THRESHOLD_FREE 使用）
     */
    @Column(name = "free_threshold")
    private Long freeThreshold;

    /**
     * 模板状态（ENABLED / DISABLED）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FreightTemplateStatus status;

    /**
     * 默认模板身份标记（店铺兜底回退锚点；普通模板恒 false，默认模板
     * 由开店编排唯一创建入口定型 true——同店铺至多一条由创建入口守卫
     * 保证，DB 唯一约束一期不做）
     */
    @Column(nullable = false, name = "is_default")
    private Boolean isDefault;

    /**
     * 所属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 设置所属店铺 ID。
     *
     * @param shopId 店铺 ID
     */
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    /**
     * 模板名称。
     *
     * @return 模板名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置模板名称。
     *
     * @param name 模板名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 运费规则类型。
     *
     * @return 规则类型
     */
    public FreightRuleType getRuleType() {
        return ruleType;
    }

    /**
     * 设置运费规则类型。
     *
     * @param ruleType 规则类型
     */
    public void setRuleType(FreightRuleType ruleType) {
        this.ruleType = ruleType;
    }

    /**
     * 按件单价（分）。
     *
     * @return 单价；非按件规则返回 null
     */
    public Long getPerItemPrice() {
        return perItemPrice;
    }

    /**
     * 设置按件单价（分）。
     *
     * @param perItemPrice 单价
     */
    public void setPerItemPrice(Long perItemPrice) {
        this.perItemPrice = perItemPrice;
    }

    /**
     * 基础运费（分）。
     *
     * @return 基础运费；非满额免邮规则返回 null
     */
    public Long getBaseFreight() {
        return baseFreight;
    }

    /**
     * 设置基础运费（分）。
     *
     * @param baseFreight 基础运费
     */
    public void setBaseFreight(Long baseFreight) {
        this.baseFreight = baseFreight;
    }

    /**
     * 免邮阈值（分）。
     *
     * @return 阈值；非满额免邮规则返回 null
     */
    public Long getFreeThreshold() {
        return freeThreshold;
    }

    /**
     * 设置免邮阈值（分）。
     *
     * @param freeThreshold 阈值
     */
    public void setFreeThreshold(Long freeThreshold) {
        this.freeThreshold = freeThreshold;
    }

    /**
     * 模板状态。
     *
     * @return 状态
     */
    public FreightTemplateStatus getStatus() {
        return status;
    }

    /**
     * 设置模板状态。
     *
     * @param status 状态
     */
    public void setStatus(FreightTemplateStatus status) {
        this.status = status;
    }

    /**
     * 默认模板身份标记。
     *
     * @return true 默认模板
     */
    public Boolean getIsDefault() {
        return isDefault;
    }

    /**
     * 设置默认身份标记。
     *
     * @param isDefault 默认身份标记
     */
    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }
}