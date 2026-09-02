package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

/**
 * 运费模板聚合根（catalog 域，独立聚合）：模板规则（包邮/按件/满额免邮，
 * 三规则互斥）+ 计费参数 + 状态（启用/停用）+ 店铺归属（shopId）。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无直接字段变更路径）：
 * <ol>
 *     <li>模板名称非空；</li>
 *     <li>计费参数与规则类型恒一致（按规则归一化：FREE 无参数、PER_ITEM 仅按件
 *         单价、THRESHOLD_FREE 仅基础运费与免邮阈值；参数非正拒绝、多余参数
 *         清理归空）；</li>
 *     <li>停用模板禁止用于新订单计费——守卫由运费计算器承载
 *         （{@code FreightCalculator} 对停用模板拒绝计费）；</li>
 *     <li>启用/停用二态切换幂等（重复设置同一状态无副作用）。</li>
 * </ol>
 * 跨上下文协作：订单域结算试算经计算器只读消费本聚合（契约冻结面）。
 * 金额单位均为分。
 *
 * @author nona9961
 */
public class FreightTemplate {

    /**
     * 模板 ID（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 归属店铺 ID（创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 模板名称
     */
    private String name;

    /**
     * 运费规则类型
     */
    private FreightRuleType ruleType;

    /**
     * 按件单价（分；仅 PER_ITEM 使用，其余规则为 null）
     */
    private Long perItemPrice;

    /**
     * 基础运费（分；仅 THRESHOLD_FREE 使用——未达免邮阈值时收取）
     */
    private Long baseFreight;

    /**
     * 免邮阈值（分；仅 THRESHOLD_FREE 使用——商品金额达阈值即包邮）
     */
    private Long freeThreshold;

    /**
     * 模板状态
     */
    private FreightTemplateStatus status;

    /**
     * 构造运费模板（新建与加载重建共用）：名称校验、计费参数按规则
     * 归一化与校验（不变量收敛点）。新建路径由工厂生成 ID 并定型初始
     * 启用状态；加载路径原样恢复持久化状态。
     *
     * @param id            模板 ID
     * @param shopId        归属店铺 ID
     * @param name          模板名称（非空）
     * @param ruleType      运费规则类型（非空）
     * @param perItemPrice  按件单价（分；PER_ITEM 必填正数）
     * @param baseFreight   基础运费（分；THRESHOLD_FREE 必填正数）
     * @param freeThreshold 免邮阈值（分；THRESHOLD_FREE 必填正数）
     * @param status        模板状态
     */
    public FreightTemplate(Long id, Long shopId, String name, FreightRuleType ruleType,
                           Long perItemPrice, Long baseFreight, Long freeThreshold,
                           FreightTemplateStatus status) {
        this.id = id;
        this.shopId = shopId;
        this.name = requireName(name);
        this.ruleType = requireRuleType(ruleType);
        applyPriceParams(perItemPrice, baseFreight, freeThreshold);
        this.status = status;
    }

    /**
     * 模板 ID。
     *
     * @return 模板 ID
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
     * 模板名称。
     *
     * @return 模板名称
     */
    public String getName() {
        return name;
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
     * 按件单价（分）。
     *
     * @return 单价；非按件规则返回 null
     */
    public Long getPerItemPrice() {
        return perItemPrice;
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
     * 免邮阈值（分）。
     *
     * @return 阈值；非满额免邮规则返回 null
     */
    public Long getFreeThreshold() {
        return freeThreshold;
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
     * 模板是否启用（新建订单可用）。
     *
     * @return true 启用
     */
    public boolean isEnabled() {
        return status == FreightTemplateStatus.ENABLED;
    }

    /**
     * 更新模板规则：名称与规则整体替换（状态保持）。
     * 计费参数按新规则归一化与校验。
     *
     * @param name          新模板名称（非空）
     * @param ruleType      新规则类型（非空）
     * @param perItemPrice  按件单价（分；PER_ITEM 必填正数）
     * @param baseFreight   基础运费（分；THRESHOLD_FREE 必填正数）
     * @param freeThreshold 免邮阈值（分；THRESHOLD_FREE 必填正数）
     */
    public void updateRules(String name, FreightRuleType ruleType,
                            Long perItemPrice, Long baseFreight, Long freeThreshold) {
        this.name = requireName(name);
        this.ruleType = requireRuleType(ruleType);
        applyPriceParams(perItemPrice, baseFreight, freeThreshold);
    }

    /**
     * 启用模板（幂等：已启用保持不变）。
     */
    public void enable() {
        this.status = FreightTemplateStatus.ENABLED;
    }

    /**
     * 停用模板（幂等：已停用保持不变）。停用后新建订单不可用——
     * 领域守卫由运费计算器承载（对停用模板拒绝计费）。
     */
    public void disable() {
        this.status = FreightTemplateStatus.DISABLED;
    }

    /**
     * 解析并校验模板名称（防御：null/空白拒绝）。
     *
     * @param name 模板名称
     * @return 模板名称
     */
    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_NAME_BLANK.code(),
                    "模板名称不能为空");
        }
        return name;
    }

    /**
     * 解析规则类型（防御：null 拒绝）。
     *
     * @param ruleType 规则类型
     * @return 规则类型
     */
    private static FreightRuleType requireRuleType(FreightRuleType ruleType) {
        if (ruleType == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_INVALID_RULES.code(),
                    "规则类型不能为空");
        }
        return ruleType;
    }

    /**
     * 计费参数按规则归一化与校验（不变量收敛点）：
     * FREE 全部归空；PER_ITEM 校验并保留按件单价；THRESHOLD_FREE 校验并
     * 保留基础运费与免邮阈值。与规则不匹配的参数一律清空，避免脏参数滞留。
     *
     * @param perItemPrice  按件单价（分）
     * @param baseFreight   基础运费（分）
     * @param freeThreshold 免邮阈值（分）
     */
    private void applyPriceParams(Long perItemPrice, Long baseFreight, Long freeThreshold) {
        switch (ruleType) {
            case FREE -> {
                this.perItemPrice = null;
                this.baseFreight = null;
                this.freeThreshold = null;
            }
            case PER_ITEM -> {
                if (perItemPrice == null || perItemPrice <= 0) {
                    throw new BusinessException(
                            EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_INVALID_RULES.code(),
                            "按件规则必须配置正数单价");
                }
                this.perItemPrice = perItemPrice;
                this.baseFreight = null;
                this.freeThreshold = null;
            }
            case THRESHOLD_FREE -> {
                if (baseFreight == null || baseFreight <= 0) {
                    throw new BusinessException(
                            EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_INVALID_RULES.code(),
                            "满额免邮规则必须配置正数基础运费");
                }
                if (freeThreshold == null || freeThreshold <= 0) {
                    throw new BusinessException(
                            EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_INVALID_RULES.code(),
                            "满额免邮规则必须配置正数阈值");
                }
                this.perItemPrice = null;
                this.baseFreight = baseFreight;
                this.freeThreshold = freeThreshold;
            }
        }
    }
}