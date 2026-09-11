package com.nona.application.support;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.RefundCallbackRecord;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundCallbackPort;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.domain.payment.repo.RefundOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.util.IDUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Service;

/**
 * 退款回调编排用例（退款回调接线阶段的核心编排：渠道 REFUND 回调 →
 * 退款单装载/核对 → 留痕 → 迁移 → 同事务推进订单与库存回补）。
 * <p>
 * 承载位依据：回调入口无身份校验（网关/系统触发，与支付回调对称），
 * 编排横跨 payment/order/inventory 三域且含跨租户写（子单/库存
 * tenant=shopId）——按设计「跨端共用编排」落 application.support；
 * 跨上下文协作全经各域端口（OrderFacade/InventoryFacade）与仓储契约，
 * 应用层承载事务边界（方法级 {@link Transactional}）与提权写段（写放行
 * 只允许出现在 application 层用例方法上）。
 * <p>
 * <b>编排语义</b>（实现接线依据，按 RefundCallbackPort 接口 javadoc +
 * RefundOrder 聚合守卫契约 + 支付回调编排先例逐条钉死）：
 * <ol>
 *     <li><b>入口守卫</b>：回调事件 null → {@code payment.gateway_callback_invalid}
 *         （渠道事故防御）；type 非 REFUND（PAY 回调）→
 *         {@code payment.gateway_callback_invalid}（支付回调归支付编排
 *         接续，本用例不消费——对称隔离）；</li>
 *     <li><b>装载</b>：按 refundNo {@code findByRefundNo} 装载退款单——
 *         不存在 → {@code payment.refund_not_found}（404，「回调先于
 *         退款单到达」的孤儿回调不产生处理路径与孤儿留痕）；</li>
 *     <li><b>防线三留痕先行</b>：回调原文构造 {@link RefundCallbackRecord}
 *         （IDUtils 生成留痕记录主键 + 收到时刻 = 当前时间）经
 *         {@link RefundOrder#appendCallbackRecord} 追加，<b>先留痕后判
 *         迁移</b>——重复/失败/金额不符等被拒回调同样留痕，对账不依赖
 *         迁移成败；</li>
 *     <li><b>防线二状态迁移</b>：result=SUCCESS → {@code markSucceeded}；
 *         result=FAIL → {@code markRefundFailed}（守卫判定顺序见
 *         RefundOrder 类 javadoc：流水异号 409 → 非 PENDING 拒绝 →
 *         金额不符 400）——<b>迁移守卫被拒的落库律</b>：同号重复回调
 *         （{@code payment.refund_status_illegal}，幂等命中）、
 *         异号冲突（{@code payment.callback_duplicate} 409）、金额不符
 *         （{@code payment.amount_mismatch}）一律先
 *         {@code repository.save}（留痕持久化，对账可查）再原样透传异常
 *         ——幂等语义成立（不重放订单/库存编排），异常到渠道的应答映射
 *         由回调接入挂点承载；</li>
 *     <li><b>成功编排（首次迁移成功后，同事务）</b>：提权写段
 *         （{@link TenantPrivilege#elevatedInTransaction}）内依序——
 *         ① 按退款单操作单元装载子单（不存在 → {@code order.sub_not_found}
 *         （404，数据异常防御））；② {@link OrderFacade#completeRefund}
 *         （子单状态分派内建：退款中 → 已退款 + 主单派生；已关闭 →
 *         发货超时路径幂等跳过——履约侧终态定格，资金侧由退款单承载；
 *         其余状态拒绝）；③ <b>未发货回补（回补判定）</b>：退款单
 *         {@code shippedAtApply = false} → 逐 SKU 按子单订单项快照装配
 *         回补明细经 {@link InventoryFacade#restore}（sold→sellable，
 *         REFUND_RESTORE 流水 + RestockEvent 触发；幂等键
 *         (order_id, sku_id, type) 兜底重复退款回调不重复回补）；
 *         {@code shippedAtApply = true} → <b>不回补</b>（已发货/已完成
 *         退款货已出，退货退补为售后深化扩展项，此处留接口位）；
 *         任一步失败 → 异常透传 → 方法事务<b>整体回滚</b>（payment →
 *         order → inventory 三域原子，同步编排）；</li>
 *     <li><b>落库</b>：迁移 + 留痕经 {@code repository.save} 持久化
 *         （与成功编排同一方法事务；编排异常时不单独落库——事务回滚
 *         已保证原子，退款单不出现「已退款但订单未推进」的半程态）；</li>
 *     <li><b>失败回调</b>：仅 markRefundFailed + save——订单侧停留退款中
 *         （领域模型「failure keeps order in refunding (retryable)」），
 *         不推进订单/库存，买家对同一退款单重试受理。</li>
 * </ol>
 * <p>
 * 幂等语义汇总（「重复回调不重复处理」）：幂等锚点 = 退款单状态
 * 迁移守卫（同号重复回调命中 status_illegal 即已处理应答，编排不重放）；
 * 本用例不做订单侧附加短路（订单侧状态由退款单守卫上游唯一驱动）。
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做
 * 事务；提权写段（跨租户写子单/库存）只出现在本类（application 层）。
 * <p>
 * 装配声明：本类注册为容器 bean（{@code @Service}）——仓储实现
 * （order/payment 域）已接线；以构造器注入声明装配契约，单测以构造器
 * 直接装配。
 *
 * @author nona9961
 */
@Service
public class RefundCallbackUseCase implements RefundCallbackPort {

    /**
     * 退款单仓储（装载/留痕/迁移落库锚点）
     */
    private final RefundOrderRepository repository;

    /**
     * 子订单仓储（成功编排：回补明细装载）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 订单门面（成功编排：子单退款推进 + 主单派生；已关闭幂等跳过）
     */
    private final OrderFacade orderFacade;

    /**
     * 库存门面（成功编排：未发货回补 restore）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行：子单/库存
     * tenant=shopId 在回调上下文推进）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造退款回调编排用例。
     *
     * @param repository          退款单仓储（必填）
     * @param subOrderRepository  子订单仓储（必填）
     * @param orderFacade         订单门面（必填）
     * @param inventoryFacade     库存门面（必填）
     * @param tenantPrivilege     提权工具（必填）
     * @param transactionTemplate 事务模板（必填）
     */
    public RefundCallbackUseCase(RefundOrderRepository repository,
                                 SubOrderRepository subOrderRepository,
                                 OrderFacade orderFacade,
                                 InventoryFacade inventoryFacade,
                                 TenantPrivilege tenantPrivilege,
                                 TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.subOrderRepository = subOrderRepository;
        this.orderFacade = orderFacade;
        this.inventoryFacade = inventoryFacade;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 处理退款回调（成功/失败统一入口；7 步编排语义见类 javadoc）。
     * <p>
     * 落库律（对支付回调编排先例）：迁移守卫被拒（重复/异号/金额不符）→
     * 先 {@code repository.save}（留痕持久化，对账可查）再原样透传；编排
     * 异常（迁移成功后 completeRefund/restore 失败）→ 不单独落库——方法
     * 级 {@link Transactional} 整体回滚，退款单不出现「已退款但订单未
     * 推进」的半程态。
     *
     * @param callback 渠道回调校验通过后的标准化事件（handleCallback
     *                 返回，必填；type 必须为 REFUND）
     */
    @Override
    @Transactional
    public void handleRefundCallback(ValidatedCallback callback) {
        // 1. 入口守卫：null / 非 REFUND（PAY 归支付编排接续）→ 渠道事故防御，无任何动作
        if (callback == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "回调事件不能为空");
        }
        if (callback.type() != CallbackType.REFUND) {
            throw new BusinessException(
                    EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "退款回调处理端口只消费 REFUND 回调（PAY 归支付回调编排接续）");
        }
        // 2. 装载：孤儿回调（回调先于退款单到达）不产生处理路径与孤儿留痕
        final RefundOrder refundOrder = repository.findByRefundNo(callback.refundNo());
        if (refundOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_NOT_FOUND.code(),
                    "退款单不存在（孤儿回调不产生处理路径）", 404);
        }
        // 3. 防线三留痕先行：回调原文构造留痕记录追加——先留痕后判迁移，
        //    重复/失败/金额不符等被拒回调同样留痕，对账不依赖迁移成败
        final RefundCallbackRecord record = new RefundCallbackRecord(
                IDUtils.generateID(), refundOrder.getId(), callback.type(), callback.payNo(),
                callback.refundNo(), callback.result(), callback.channelTxnNo(),
                callback.amountCents(), Instant.now());
        refundOrder.appendCallbackRecord(record);
        // 4. 防线二状态迁移（守卫判定顺序见 RefundOrder 类 javadoc）——迁移守卫
        //    被拒（status_illegal/callback_duplicate/amount_mismatch）：先落库留痕
        //    再原样透传（幂等命中不重放订单/库存编排）
        if (callback.result() == GatewayResult.SUCCESS) {
            try {
                refundOrder.markSucceeded(callback.channelTxnNo(), callback.amountCents());
            } catch (final BusinessException e) {
                // 落库律（决策 5/6）：被拒回调同样留痕——独立事务提交（REQUIRES_NEW），
                // 不被方法级 @Transactional 的整体回滚吃掉（对账不依赖迁移成败）
                transactionTemplate.execute(status -> {
                    repository.save(refundOrder);
                    return null;
                });
                throw e;
            }
            // 5. 成功编排（首次迁移成功后）：提权写段（回调上下文
            //    无买家身份、tenant 空，推进店铺数据必须放行）内依序 completeRefund
            //    （子单装载与 404 契约防御内建于订单门面：退款中 → 已退款 + 主单派生
            //    / 已关闭幂等跳过 / 其余拒绝）→ 按操作单元装载子单（不存在 404 数据
            //    异常防御）→ 未发货回补（判定：shippedAtApply=false → restore，
            //    明细 = 子单订单项快照；已发货不回补，退货物流后续扩展留接口位）→ 根行
            //    迁移 + 留痕落库——编排与落库同提权事务（三域原子：杜绝「编排
            //    已提交而退款单未迁移」的部分提交窗口）
            try {
                tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                    orderFacade.completeRefund(refundOrder.getSubOrderId());
                    final SubOrder subOrder =
                            subOrderRepository.getByID(refundOrder.getSubOrderId());
                    if (subOrder == null) {
                        throw new BusinessException(
                                EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                                "子订单不存在（数据异常防御）", 404);
                    }
                    if (!refundOrder.isShippedAtApply()) {
                        restoreInventory(subOrder);
                    }
                    repository.save(refundOrder);
                    return null;
                });
            } catch (final RuntimeException e) {
                // 编排异常（聚合守卫/库存回补失败）原样透传 → 方法事务整体回滚
                // （三域原子）；不单独落库——退款单不出现「已退款但订单未
                // 推进」的半程态
                throw e;
            } catch (final Exception e) {
                throw new IllegalStateException("退款回调编排提权事务失败", e);
            }
        } else {
            // 7. 失败回调：仅 markRefundFailed——订单侧停留退款中（可重试），
            //    不推进订单/库存；状态迁移 + 留痕落库同外层方法事务（refund
            //    global 行无提权需要，无编排段故无部分提交窗口）
            try {
                refundOrder.markRefundFailed();
                repository.save(refundOrder);
            } catch (final BusinessException e) {
                // 落库律（决策 5/6）：被拒回调同样留痕——独立事务提交（REQUIRES_NEW），
                // 不被方法级 @Transactional 的整体回滚吃掉（对账不依赖迁移成败）
                transactionTemplate.execute(status -> {
                    repository.save(refundOrder);
                    return null;
                });
                throw e;
            }
        }
    }

    /**
     * 未发货回补：调用方已装载退款操作单元子单（不存在 404 防御在提权
     * 段内先行）——订单项快照装配回补明细（SKU + 数量，与下单预占/支付扣减/
     * 取消回滚的 items 装配同源对称）→ InventoryFacade.restore（sold →
     * sellable，REFUND_RESTORE 流水 + RestockEvent 触发面；幂等键
     * (order_id, sku_id, type) 兜底重复退款回调不重复回补）。已发货
     * （shippedAtApply=true）不回补——本方法仅被未发货分支调用。
     *
     * @param subOrder 退款操作单元子单（订单项快照装配回补明细）
     */
    private void restoreInventory(SubOrder subOrder) {
        final List<StockChangeItem> items = subOrder.getItems().stream()
                .map(item -> new StockChangeItem(item.getSkuId(), item.getQuantity()))
                .toList();
        inventoryFacade.restore(subOrder.getId(), items);
    }
}