package com.nona.api.mall;

import java.time.Instant;
import java.util.List;

/**
 * 下单结果（买家端契约，B7.6 提交订单视图）：主单号/按店铺拆分的子单
 * 列表/待支付支付单——提交成功即进入待支付，前端跳收银台（M08）。
 * <p>
 * 语义：N 店铺 → N 子单（B7.5①）；子单金额与商品快照已固化（B7.6②，
 * 防改价错乱）；支付单为待支付状态并已注册支付超时截止时间
 * （B8.3 支付超时 30 分钟，收银台展示 deadline）。金额单位均为分。
 *
 * @param masterOrderId 主订单 ID
 * @param orderNo       主订单号（TD-13：ORD + 日期 + snowflake 后段）
 * @param subOrders     子单列表（按店铺拆单，保持创建序）
 * @param payment       待支付支付单视图
 * @author nona9961
 */
public record OrderResult(Long masterOrderId, String orderNo, List<SubOrderResult> subOrders,
                          PaymentResult payment) {

    /**
     * 子订单视图（店铺维度配货/履约单元展示）。
     *
     * @param subOrderId   子订单 ID
     * @param subOrderNo   子订单号（TD-13 业务单号）
     * @param shopId       归属店铺 ID
     * @param shopName     店铺名（B7.5③ 展示子订单归属店铺）
     * @param goodsAmount  商品金额（分）
     * @param freightAmount 运费（分，该店模板计得）
     * @param paidAmount   实付（分，商品金额 + 运费 - 优惠）
     */
    public record SubOrderResult(Long subOrderId, String subOrderNo, Long shopId,
                                 String shopName, long goodsAmount, long freightAmount,
                                 long paidAmount) {
    }

    /**
     * 待支付支付单视图（关联主单，金额 = 主单实付）。
     *
     * @param paymentOrderId 支付单 ID
     * @param payNo          支付单号（TD-13：PAY + 日期 + snowflake 后段）
     * @param amount         支付金额（分）
     * @param timeoutAt      支付超时截止时间（B8.3：创建时刻 + 30 分钟）
     */
    public record PaymentResult(Long paymentOrderId, String payNo, long amount,
                                Instant timeoutAt) {
    }
}