package com.nona.domain.order.entity;

import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

/**
 * 金额明细（order 域值对象，不可变，金额单位均为分）：子订单的金额
 * 构成——商品总额/运费/实付，预留优惠字段位（当前无优惠叠加，营销
 * 后续叠加启用，结构免改）。
 * <p>
 * 运费来源（跨域契约消费面）：运费由 catalog/order 共用领域服务
 * {@code FreightCalculator}（catalog 域 ports，三规则：包邮 0/按件单价
 * ×件数/满额免邮）按店铺运费模板计算——经用例层跨域编排后以参数传入
 * 本 VO（域内不感知 catalog 端口，与购物车可售上限同模式：跨上下文
 * 数据经用例层读入、以参数断言收敛）。运费单位分与计算器契约一致。
 * <p>
 * 冻结语义：创建后不可变（金额固化，防改价错乱）。
 * <p>
 * 结构不变量（收敛在构造路径）：
 * <ol>
 *     <li>商品总额/运费/优惠/实付全部非负；</li>
 *     <li>实付 == 商品总额 + 运费 - 优惠（金额恒等式；优惠超过商品+运费
 *         时等式仍可成立但实付为负——额外断言实付非负拦截负订单）；</li>
 *     <li>当前优惠恒 0（装配契约由用例层承载，本 VO 不设非零拒绝——
 *         预留位随后续叠加启用，模型免改）。</li>
 * </ol>
 *
 * @author nona9961
 */
public class AmountDetail {

    /**
     * 商品总额（分：Σ 订单项快照单价 × 数量）
     */
    private final long goodsAmount;

    /**
     * 运费（分：经运费计算器按店铺模板算得）
     */
    private final long freightAmount;

    /**
     * 优惠金额（分，位预留：当前恒 0，营销叠加时启用）
     */
    private final long discount;

    /**
     * 实付金额（分：商品总额 + 运费 - 优惠）
     */
    private final long paidAmount;

    /**
     * 构造金额明细（金额自洽校验收敛在本构造路径）。
     *
     * @param goodsAmount   商品总额（分，非负）
     * @param freightAmount 运费（分，非负）
     * @param discount      优惠金额（分，非负；当前恒 0）
     * @param paidAmount    实付金额（分，必须等于商品总额 + 运费 - 优惠且非负）
     */
    public AmountDetail(long goodsAmount, long freightAmount, long discount, long paidAmount) {
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code(),
                goodsAmount >= 0, "金额明细商品总额不能为负：{}", goodsAmount);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code(),
                freightAmount >= 0, "金额明细运费不能为负：{}", freightAmount);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code(),
                discount >= 0, "金额明细优惠金额不能为负：{}", discount);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code(),
                paidAmount == goodsAmount + freightAmount - discount,
                "金额明细实付与商品总额+运费-优惠不符：{} != {} + {} - {}",
                paidAmount, goodsAmount, freightAmount, discount);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code(),
                paidAmount >= 0, "金额明细实付不能为负：{}", paidAmount);
        this.goodsAmount = goodsAmount;
        this.freightAmount = freightAmount;
        this.discount = discount;
        this.paidAmount = paidAmount;
    }

    /**
     * 商品总额（分）。
     *
     * @return 商品总额
     */
    public long getGoodsAmount() {
        return goodsAmount;
    }

    /**
     * 运费（分）。
     *
     * @return 运费
     */
    public long getFreightAmount() {
        return freightAmount;
    }

    /**
     * 优惠金额（分）。
     *
     * @return 优惠金额；当前恒 0
     */
    public long getDiscount() {
        return discount;
    }

    /**
     * 实付金额（分）。
     *
     * @return 实付金额
     */
    public long getPaidAmount() {
        return paidAmount;
    }
}