package com.nona.domain.payment.service;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.factory.PaymentOrderFactory;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;

/**
 * PaymentPort 实现（创建/复用与关单签名语义；bean 装配
 * 与事务注解已接线）。
 * <p>
 * 接线语义（按 PaymentPort 接口 javadoc + 聚合契约）：
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
 * 装配说明：本类已注册 Spring bean（{@code @Service}）——依赖的支付单
 * 仓储接口已有 JPA 实现接线。
 *
 * @author nona9961
 */
@Service
public class PaymentPortImpl implements PaymentPort {

    /**
     * 当前唯一支付渠道（真实渠道扩展位：字段保留，单据创建时定型）。
     */
    private static final String MOCK_CHANNEL = "MOCK";

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
        if (masterOrderId == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code(),
                    "主订单 ID 不能为空（支付单一对一锚点）");
        }
        final PaymentOrder existing = repository.findByOrderId(masterOrderId);
        if (existing == null) {
            final PaymentOrder created = new PaymentOrderFactory()
                    .create(masterOrderId, paidAmount, MOCK_CHANNEL, payTimeoutMillis);
            repository.save(created);
            return new PendingPayment(created.getId(), created.getPayNo(),
                    created.getAmount(), payTimeoutMillis, created.getTimeoutAt());
        }
        if (existing.getStatus() != PaymentOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code(),
                    "同主单已存在非待支付支付单（一对一，防重复发起）");
        }
        return new PendingPayment(existing.getId(), existing.getPayNo(),
                existing.getAmount(), payTimeoutMillis, existing.getTimeoutAt());
    }

    /**
     * 待支付支付单关单（语义见类 javadoc 与 PaymentPort#closePay）。
     *
     * @param payNo 支付单号
     */
    @Override
    public void closePay(String payNo) {
        if (payNo == null || payNo.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单号不能为空");
        }
        final PaymentOrder order = repository.findByPayNo(payNo);
        if (order == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单不存在");
        }
        if (order.getStatus() == PaymentOrderStatus.CLOSED) {
            return;
        }
        order.close();
        repository.save(order);
    }
}