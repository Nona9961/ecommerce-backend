package com.nona.domain.payment.service;

import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.domain.payment.repo.PaymentOrderRepository;

/**
 * PaymentPort 实现骨架（红阶段冻结签名语义，方法体 UOE；实现接线
 * 归绿阶段）。
 * <p>
 * 接线语义（按 PaymentPort 接口 javadoc + 聚合契约，绿阶段实现依据）：
 * <ul>
 *     <li><b>createPendingPayment</b>（历史遗留契约，实现接线归支付域落位）：
 *         按 orderId 装载（findByOrderId）——已存在待支付支付单 → <b>复用</b>
 *         返回既有视图（不重复创建，order_id 唯一约束兜底并发双建）；
 *         已存在非待支付（已支付/已失败/已关闭）→ 拒绝
 *         {@code payment.order_invalid}（一对一主单语义，防重复发起）；
 *         不存在 → 经 PaymentOrderFactory.create 新建（ID/payNo 生成、
 *         timeoutAt = now + payTimeoutMillis）→ 落库 → 返回 PendingPayment
 *         视图（paymentOrderId/payNo/amount/payTimeoutMillis 回显/timeoutAt）；</li>
 *     <li><b>closePay</b>：按 payNo 装载——不存在 → {@code payment.not_found}
 *         （404）；待支付/已失败 → close() 迁移 + 落库；已关闭 → 幂等成功
 *         （无需落库）；已支付 → {@code payment.status_illegal}。</li>
 * </ul>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的仓储
 * 接口当前无实现，注册即装配错误；绿阶段仓储实现落地后补注解并复验
 * 上下文）。
 *
 * @author nona9961
 */
public class PaymentPortImpl implements PaymentPort {

    /**
     * 支付单仓储（装载/落库锚点）
     */
    private final PaymentOrderRepository repository;

    /**
     * 构造端口实现骨架。
     *
     * @param repository 支付单仓储（必填）
     */
    public PaymentPortImpl(PaymentOrderRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建/复用待支付支付单并注册支付超时 deadline（语义见类 javadoc）。
     *
     * @param masterOrderId   主订单 ID
     * @param paidAmount      支付金额（分，= 主单实付）
     * @param payTimeoutMillis 支付超时时长（毫秒）
     * @return 待支付支付单视图
     */
    @Override
    public PendingPayment createPendingPayment(Long masterOrderId, long paidAmount,
                                               long payTimeoutMillis) {
        throw new UnsupportedOperationException("红阶段契约：createPendingPayment 实现留绿阶段（PaymentPortImpl 创建/复用）");
    }

    /**
     * 待支付支付单关单（语义见类 javadoc 与 PaymentPort#closePay）。
     *
     * @param payNo 支付单号
     */
    @Override
    public void closePay(String payNo) {
        throw new UnsupportedOperationException("红阶段契约：closePay 实现留绿阶段（PaymentPortImpl 关单）");
    }
}