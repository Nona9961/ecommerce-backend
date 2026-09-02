package com.nona.domain.catalog.entity;

/**
 * 运费规则类型（三规则互斥，不支持组合）：
 * <ul>
 *     <li>{@link #FREE} 包邮：运费恒 0（不计件数与金额）；</li>
 *     <li>{@link #PER_ITEM} 按件：运费 = 按件单价 × 件数；</li>
 *     <li>{@link #THRESHOLD_FREE} 满额免邮：订单商品金额达阈值即免邮（0），
 *         未达额收基础运费。</li>
 * </ul>
 * 金额单位均为分。规则类型是模板规则校验与运费计算的分支依据；
 * 对应的计费参数与规则匹配由 {@link FreightTemplate} 收敛校验。
 *
 * @author nona9961
 */
public enum FreightRuleType {

    /**
     * 包邮：运费恒 0。
     */
    FREE,

    /**
     * 按件：运费 = 按件单价 × 件数。
     */
    PER_ITEM,

    /**
     * 满额免邮：订单金额达阈值即免邮，未达额收基础运费。
     */
    THRESHOLD_FREE
}