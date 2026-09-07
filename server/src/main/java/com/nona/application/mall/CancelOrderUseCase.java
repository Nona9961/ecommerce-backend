package com.nona.application.mall;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 取消订单编排用例（买家主动取消 B8.6 ① + 支付超时自动取消 B8.3，
 * 跨上下文同事务：order.cancel → inventory.rollback → payment.closePay）。
 * <p>
 * <b>编排序</b>（设计 4.3 钉死，超时调度与主动取消共用）：
 * <ol>
 *     <li><b>主动取消入口（cancelByBuyer）</b>：按 orderId 装载主单——
 *         不存在或归属买家不符 → 按不存在呈现（{@code order.master_not_found}
 *         404，防越权与存在性泄露，PaymentUseCase 发起支付同先例）；
 *         超时入口（cancelByTimeout）为系统调度触发（无买家身份），
 *         主单不存在同样 404（数据异常防御，不静默）；</li>
 *     <li><b>幂等短路</b>：主单整体状态已为 {@code CANCELLED}（全部子单
 *         已取消的派生结果）→ 直接返回成功，不重复调用订单推进/库存
 *         回滚/支付关单——主动取消重放与超时调度重扫（handler 幂等）的
 *         常态路径，与 {@code PaymentPort.closePay} 已关闭幂等对齐；
 *         短路的并发窗口由数据库层兜底（乐观锁/幂等键，详见各域契约）；</li>
 *     <li><b>提权写段</b>：跨租户写（子单 tenant=shopId 状态推进 + 库存
 *         tenant=shopId 回滚 + 支付单 global 关单）在
 *         {@link TenantPrivilege#elevatedInTransaction} 内整体执行——
 *         任一失败整体回滚（方法级 {@link Transactional} 统辖），买家
 *         视角取消店铺数据必须提权（TD-12，PlaceOrder 同构）；</li>
 *     <li><b>订单侧推进</b>：{@link OrderFacade#cancel} 逐子单
 *         （仅待支付可取消；已支付/已发货非法迁移由聚合守卫拒绝
 *         {@code order.sub_status_illegal}——B8.6 ②③ 语义内建）+ 主单
 *         派生（全部取消 → 主单已取消）；</li>
 *     <li><b>库存回滚</b>（no stock leaks）：逐子单按订单项快照装配回滚
 *         明细（SKU + 数量，与下单预占{@code preoccupy} 的 items 装配
 *         同源对称）→ {@link InventoryFacade#rollback}（幂等键
 *         (order_id, sku_id, type) 兜底重复回滚）；整单取消语义——主单
 *         下全部子单逐单回滚，无子单粒度部分取消入口；</li>
 *     <li><b>支付关单 hook</b>：经 {@code PaymentOrderRepository#findByOrderId}
 *         取该主单支付单号 → {@link PaymentPort#closePay}（待支付/失败
 *         关闭、已关闭幂等、已支付拒绝——支付侧幂等语义已冻结）；支付单
 *         <b>不存在时跳过关单</b>（防御跳闸：主目标是订单+库存，缺支付单
 *         的数据异常不阻塞取消，杜绝库存泄漏死锁）。</li>
 * </ol>
 * <b>超时复用面（冻结）</b>：支付超时 handler（消费编排 WU）以
 * {@link #cancelByTimeout} 为唯一入口复用本编排（reason =
 * {@link #REASON_TIMEOUT}），不感知内部细节；主动取消入口保留买家归属
 * 校验，超时入口无身份校验。
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做事务。
 * 租户纪律：写放行（elevatedInTransaction）只出现在本类（application
 * 层），domain 内不放行；读放行（{@code @CrossTenant}）本用例不需要
 * （主单/支付单为 global 表，子单/回滚读在提权事务段内）。
 * <p>
 * 装配声明：用例类<b>不注册为容器 bean</b>——订单侧端口实现与
 * MasterOrder/SubOrder/PaymentOrder 仓储实现未接线（红阶段装配学习，
 * 同 PlaceOrderUseCase）；以构造器注入声明装配契约，Spring 注册
 * （{@code @Service}）随接线 WU 落位恢复；单测以构造器直接装配。
 *
 * @author nona9961
 */
public class CancelOrderUseCase {

    /**
     * 支付超时自动取消的原因标记（设计 4.3：超时编排 + 原因 = TIMEOUT；
     * 消费编排 WU 复用本常量而非裸字符串）。
     */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /**
     * 主订单仓储（归属校验 + 幂等短路判定 + 主单装载）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 子订单仓储（逐子单回滚明细装载）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 订单门面（订单侧状态推进与主单派生）
     */
    private final OrderFacade orderFacade;

    /**
     * 库存门面（预占回滚，幂等键兜底）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 支付单仓储（按主单取支付单号 → closePay）
     */
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 支付端口（待支付/失败关单，已关闭幂等）
     */
    private final PaymentPort paymentPort;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造取消编排用例。
     *
     * @param masterOrderRepository   主订单仓储
     * @param subOrderRepository      子订单仓储
     * @param orderFacade             订单门面
     * @param inventoryFacade         库存门面
     * @param paymentOrderRepository  支付单仓储
     * @param paymentPort             支付端口
     * @param tenantPrivilege         提权工具
     * @param transactionTemplate     事务模板
     */
    public CancelOrderUseCase(MasterOrderRepository masterOrderRepository,
                              SubOrderRepository subOrderRepository,
                              OrderFacade orderFacade,
                              InventoryFacade inventoryFacade,
                              PaymentOrderRepository paymentOrderRepository,
                              PaymentPort paymentPort,
                              TenantPrivilege tenantPrivilege,
                              TransactionTemplate transactionTemplate) {
        this.masterOrderRepository = masterOrderRepository;
        this.subOrderRepository = subOrderRepository;
        this.orderFacade = orderFacade;
        this.inventoryFacade = inventoryFacade;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentPort = paymentPort;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 买家主动取消（B8.6 ①：待支付直接取消 + 库存回滚 + 支付关单）。
     * <p>
     * 归属校验先于幂等短路：主单不存在或归属买家不符 → 404 按不存在
     * 呈现（防越权与存在性泄露）；校验通过后复用共享编排
     * {@link #cancelInternal}（含幂等短路）。
     *
     * @param buyerId      当前买家账号 ID（认证上下文，与发起支付对称）
     * @param masterOrderId 主订单 ID（必填）
     * @param reason       取消原因（可空——买家不填原因时传 null，透传
     *                     订单门面）
     */
    @Transactional
    public void cancelByBuyer(Long buyerId, Long masterOrderId, String reason) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(masterOrderId);
        if (masterOrder == null || !Objects.equals(masterOrder.getBuyerId(), buyerId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在或归属不符", 404);
        }
        cancelInternal(masterOrderId, reason, masterOrder.getStatus());
    }

    /**
     * 支付超时自动取消（B8.3：预占库存自动回滚 + 支付关单；调度引擎/
     * handler 复用入口，reason = {@link #REASON_TIMEOUT}）。
     * <p>
     * 系统触发无买家身份，不做归属校验；主单不存在 → 404（数据异常
     * 防御，不静默）；主单已取消 → 幂等短路成功（超时重扫常态路径）。
     *
     * @param masterOrderId 主订单 ID（必填）
     */
    @Transactional
    public void cancelByTimeout(Long masterOrderId) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(masterOrderId);
        if (masterOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在", 404);
        }
        cancelInternal(masterOrderId, REASON_TIMEOUT, masterOrder.getStatus());
    }

    /**
     * 共享取消编排（编排序见类 javadoc；幂等短路 → 提权写段内：
     * orderFacade.cancel → 逐子单 rollback → closePay hook）。
     * <p>
     * 幂等短路以入口装载的主单状态判定（读 global 表，事务外或事务内
     * 均可）；短路成功后不再触发任何写动作（不重复推进/回滚/关单）。
     *
     * @param masterOrderId 主订单 ID
     * @param reason        取消原因（透传订单门面；超时入口为 REASON_TIMEOUT）
     * @param masterStatus  主单当前整体状态（入口装载值，短路判定依据）
     */
    private void cancelInternal(Long masterOrderId, String reason,
                                MasterOrderStatus masterStatus) {
        if (masterStatus == MasterOrderStatus.CANCELLED) {
            return;
        }
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                orderFacade.cancel(masterOrderId, reason);
                rollbackInventory(masterOrderId);
                closePayment(masterOrderId);
                return null;
            });
        } catch (final RuntimeException e) {
            // 业务异常（含聚合守卫拒绝/库存回滚失败）原样透传 → 方法级
            // @Transactional 整体回滚；仅受检异常包装（提权工具契约）
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("取消编排提权事务失败", e);
        }
    }

    /**
     * 逐子单库存回滚（no stock leaks）：子单订单项快照装配回滚明细
     * （SKU + 数量，与预占 preoccupy items 同源对称）→ 逐子单
     * {@link InventoryFacade#rollback}（幂等键 (order_id, sku_id, type)
     * 兜底重复回滚）；主单下全部子单逐单回滚，无子单粒度部分取消入口。
     *
     * @param masterOrderId 主订单 ID（子单按 master_order_id 反查）
     */
    private void rollbackInventory(Long masterOrderId) {
        final List<SubOrder> subOrders =
                subOrderRepository.getByMasterOrderId(masterOrderId);
        for (final SubOrder subOrder : subOrders) {
            final List<StockChangeItem> items = subOrder.getItems().stream()
                    .map(item -> new StockChangeItem(item.getSkuId(), item.getQuantity()))
                    .toList();
            inventoryFacade.rollback(subOrder.getId(), items);
        }
    }

    /**
     * 支付关单 hook：经 {@link PaymentOrderRepository#findByOrderId} 取该
     * 主单支付单号 → {@link PaymentPort#closePay}；支付单<b>不存在时跳过
     * 关单</b>（防御跳闸——主目标是订单+库存，缺支付单的数据异常不阻塞
     * 取消，杜绝库存泄漏死锁）。
     *
     * @param masterOrderId 主订单 ID（支付单关联锚点，一对一）
     */
    private void closePayment(Long masterOrderId) {
        final PaymentOrder paymentOrder =
                paymentOrderRepository.findByOrderId(masterOrderId);
        if (paymentOrder != null) {
            paymentPort.closePay(paymentOrder.getPayNo());
        }
    }
}
