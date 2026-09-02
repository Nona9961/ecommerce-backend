package com.nona.domain.catalog.ports;

import com.nona.domain.catalog.entity.FreightTemplate;

/**
 * 运费计算器（catalog / order 共用领域服务，跨上下文契约冻结面）。
 * <p>
 * 契约冻结说明：本接口方法签名与语义作为订单域结算试算的消费契约，
 * 由运费模板聚合的宿主域（catalog）提供实现；订单域只依赖本接口
 * （跨域经 ports 协作，无直接仓储访问）。实现位置：catalog 域领域服务
 * （纯计算，无基础设施依赖）。
 * <p>
 * 三规则计算语义（金额单位均为分）：
 * <ul>
 *     <li>FREE（包邮）：运费恒 0——不计件数与金额；</li>
 *     <li>PER_ITEM（按件）：运费 = 按件单价 × 件数（0 件 → 0）；</li>
 *     <li>THRESHOLD_FREE（满额免邮）：商品金额 ≥ 免邮阈值 → 0（达标即包邮，
 *         含恰好达额）；低于阈值 → 基础运费。</li>
 * </ul>
 * 领域守卫：停用模板拒绝计费（停用模板新建订单不可用）；负金额/负件数/
 * 空模板为防御性拒绝。历史订单不受影响（金额在订单侧快照固化）。
 *
 * @author nona9961
 */
public interface FreightCalculator {

    /**
     * 按模板计算运费。
     *
     * @param template   启用状态下的运费模板（停用/空模板拒绝）
     * @param itemAmount 子单商品金额（分，非负；满额免邮规则按此判定达标）
     * @param itemCount  子单商品总件数（非负；按件规则按此计费）
     * @return 运费金额（分）
     */
    long calculate(FreightTemplate template, long itemAmount, int itemCount);
}