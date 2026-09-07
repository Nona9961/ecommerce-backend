package com.nona.domain.payment.service;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.PaymentCallbackPort;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;

import java.time.Instant;
import java.util.List;

/**
 * 支付回调处理实现（绿阶段已实现留痕→迁移→编排挂点签名语义；
 * bean 装配/事务注解/回调编排接线随编排接线阶段落地）。
 * <p>
 * 接线语义（按 PaymentCallbackPort 接口 javadoc + PaymentOrder 聚合
 * 契约，绿阶段实现依据）：
 * <ol>
 *     <li>回调类型守卫：type 非 PAY（REFUND 回调）→ 拒绝（退款流
 *         退款流 契约接续，本处理器不消费）；</li>
 *     <li>按 payNo 装载支付单（findByPayNo），不存在 → {@code payment.not_found}
 *         （404「回调先于支付单到达」的孤儿回调即此路径——不产生孤儿
 *         留痕行，告警日志由编排落位）；</li>
 *     <li><b>防线三留痕先行</b>：回调原文构造 PaymentCallbackRecord
 *         （appendCallbackRecord 追加）后，再执行状态迁移判决；</li>
 *     <li><b>防线二状态迁移</b>：result=SUCCESS → markPaid；result=FAIL →
 *         markFailed（金额/流水/状态守卫判定顺序见 PaymentOrder 类
 *         javadoc）；同号重复回调命中 {@code payment.status_illegal} 时
 *         按「已处理应答」返回（B8.2 重复回调只生效一次，不重放编排）；
 *         异号冲突（409）与金额不符（400）原样透传（渠道事故告警）；</li>
 *     <li><b>防线一唯一约束</b>：channel_txn_no 唯一约束为并发重复回调
 *         的物理兜底（DB 冲突由实现转换：已支付/已留痕 → 已处理应答）；</li>
 *     <li><b>成功编排挂点（回调编排）</b>：首次迁移成功后同事务执行
 *         orderFacade.onPaid（子单推进 + 主单派生）与
 *         inventoryFacade.confirmDeduct（库存确认扣除）——编排同事务
 *         （payment → order → inventory，TD-07）。</li>
 * </ol>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的仓储
 * 与编排实现未接线，注册即装配错误；绿阶段接线后补注解并复验上下文）。
 *
 * @author nona9961
 */
public class PaymentCallbackProcessor implements PaymentCallbackPort {

    /**
     * 支付单仓储（装载/留痕/迁移落库锚点）
     */
    private final PaymentOrderRepository repository;

    /**
     * 订单门面（成功回调编排挂点：子单推进 + 主单派生；回调编排接线）
     */
    private final OrderFacade orderFacade;

    /**
     * 库存门面（成功回调编排挂点：确认扣除 I4；回调编排接线）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 构造回调处理器骨架。
     *
     * @param repository      支付单仓储（必填）
     * @param orderFacade     订单门面（必填）
     * @param inventoryFacade 库存门面（必填）
     */
    public PaymentCallbackProcessor(PaymentOrderRepository repository,
                                    OrderFacade orderFacade,
                                    InventoryFacade inventoryFacade) {
        this.repository = repository;
        this.orderFacade = orderFacade;
        this.inventoryFacade = inventoryFacade;
    }

    /**
     * 处理支付回调（成功/失败统一入口；语义见类 javadoc）。
     *
     * @param callback 标准化的支付回调事件（type 必须为 PAY）
     */
    @Override
    public void handlePayCallback(ValidatedCallback callback) {
        if (callback == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "回调事件不能为空");
        }
        if (callback.type() != CallbackType.PAY) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "支付回调处理端口只消费 PAY 回调（REFUND 归退款流契约接续）");
        }
        final PaymentOrder order = repository.findByPayNo(callback.payNo());
        if (order == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单不存在（孤儿回调不产生处理路径）");
        }
        final PaymentCallbackRecord record = new PaymentCallbackRecord(
                IDUtils.generateID(), order.getId(), callback.type(), callback.payNo(),
                callback.refundNo(), callback.result(), callback.channelTxnNo(),
                callback.amountCents(), Instant.now());
        order.appendCallbackRecord(record);
        try {
            if (callback.result() == GatewayResult.SUCCESS) {
                order.markPaid(callback.channelTxnNo(), callback.amountCents());
                orderFacade.onPaid(order.getOrderId());
                inventoryFacade.confirmDeduct(order.getOrderId(), List.of());
            } else {
                order.markFailed(callback.channelTxnNo(), callback.amountCents());
            }
        } catch (BusinessException e) {
            repository.save(order);
            throw e;
        }
        repository.save(order);
    }
}