package com.nona.application.mall;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.timeout.TimeoutType;

import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 发起支付用例（B8.1 承载：POST /mall/payments {orderId}）——买家对
 * 已下单主单发起支付：主单校验（C1 历史遗留接线：支付发起前必须校验
 * 主单处于可支付状态）→ 支付单创建/复用 → 渠道受理。
 * <p>
 * 编排语义（绿阶段已实现，主单校验→创建/复用→渠道受理）：
 * <ol>
 *     <li><b>主单装载与归属</b>：按 orderId 装载主单；不存在或归属买家
 *         不符 → 按不存在呈现（{@code order.master_not_found} 404，防
 *         越权与存在性泄露）；</li>
 *     <li><b>主单状态防线（C1）</b>：主单整体状态必须为
 *         {@code PENDING_PAYMENT}（可支付）——已支付/已取消/已关闭等
 *         非可支付状态再发起 → {@code payment.order_invalid}（400）；
 *         主单状态为派生效（子单投影派生），校验由主单装配路径读取；</li>
 *     <li><b>支付单创建/复用</b>：经 {@link PaymentPort#createPendingPayment}
 *         ——同主单已存在待支付单 → 复用（一次支付请求一张支付单，order_id
 *         唯一约束兜底）；不存在 → 补建（金额 = 主单实付快照，超时时长 =
 *         订单域规则值 TimeoutType.ORDER_PAY）；已存在非待支付单 → 拒绝
 *         （{@code payment.order_invalid}，防重复发起）；</li>
 *     <li><b>渠道受理</b>：以支付单号与金额构造 {@link AcquireRequest} 调
 *         {@link PaymentGateway#acquire}（异步回调模型：受理成功 ≠ 支付
 *         成功，结果经回调异步到达）→ 返回受理结果（渠道流水 + 收银台
 *         标识）。</li>
 * </ol>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的支付
 * 端口实现与回调链路未接线，注册即装配错误；绿阶段接线后按
 * PlaceOrderUseCase 同构补注册并复验上下文）。
 *
 * @author nona9961
 */
@Service
public class PaymentUseCase {

    /**
     * 支付端口（创建/复用待支付支付单）
     */
    private final PaymentPort paymentPort;

    /**
     * 支付渠道（受理锚点）
     */
    private final PaymentGateway gateway;

    /**
     * 主订单仓储（主单装载与可支付状态校验）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 构造发起支付用例。
     *
     * @param paymentPort          支付端口（必填）
     * @param gateway              支付渠道（必填）
     * @param masterOrderRepository 主订单仓储（必填）
     */
    public PaymentUseCase(PaymentPort paymentPort, PaymentGateway gateway,
                          MasterOrderRepository masterOrderRepository) {
        this.paymentPort = paymentPort;
        this.gateway = gateway;
        this.masterOrderRepository = masterOrderRepository;
    }

    /**
     * 买家对主单发起支付（B8.1；编排语义见类 javadoc）。
     *
     * @param buyerId 买家账号 ID（归属校验，必填）
     * @param orderId 主订单 ID（必填）
     * @return 渠道受理结果（渠道流水 + 收银台参数；支付结果经回调异步到达）
     */
    public AcquireResult initiatePayment(Long buyerId, Long orderId) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(orderId);
        if (masterOrder == null || !Objects.equals(buyerId, masterOrder.getBuyerId())) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在或不属于当前买家");
        }
        if (masterOrder.getStatus() != MasterOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code(),
                    "主订单处于非可支付状态，不可发起支付");
        }
        final long paidAmount = masterOrder.getAmount().getPaidAmount();
        final PendingPayment pending = paymentPort.createPendingPayment(orderId,
                paidAmount, TimeoutType.ORDER_PAY.durationMillis());
        return gateway.acquire(new AcquireRequest(pending.payNo(), pending.amount()));
    }
}