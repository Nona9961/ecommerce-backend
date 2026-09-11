package com.nona.domain.order.service;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderFacade 实现侧场景测试（cancel 接线 + autoComplete 完成接线：订单
 * 侧状态推进契约）。
 * <p>
 * 覆盖：cancel——happy 待支付整单取消 + 主单派生 + 保存（reason 透传
 * 无副作用）；critical 子单集合为空防御 404；fail 主单不存在 404、已
 * 支付/已发货/已取消/混态子单非法迁移拒绝（守卫内建，无任何
 * 保存）。autoComplete——happy 已发货子单完成 + 主单派生已完成 + 保存；
 * critical 多子单部分完成中间态派生；fail 子单不存在/主单不存在 404、
 * 未发货/重复完成守卫拒绝。库存回滚/支付关单不在本类（编排层承载，
 * 见取消/完成用例测试）；完成事件发布不在本类（编排层承载，见完成
 * 用例测试）。
 * <p>
 * 依赖装配：仓储以真实对象 + mock 承载装载面（子单/主单为真实聚合，
 * 状态迁移守卫与派生真实执行）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class OrderFacadeImplUnitTest {

    /**
     * 主订单 / 子单 / 店铺 基线
     */
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SUB_B = 102L;
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;

    @Mock
    private MasterOrderRepository masterOrderRepository;

    @Mock
    private SubOrderRepository subOrderRepository;

    /**
     * 被测门面实现（不注册 Spring；mock 注入后于实例构造，setUp
     * 装配）。
     */
    private OrderFacadeImpl orderFacade;

    /**
     * 每用例前重建被测实现（依赖为 mock，无状态跨用例残留）。
     */
    @BeforeEach
    void setUp() {
        orderFacade = new OrderFacadeImpl(masterOrderRepository, subOrderRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 待支付主单基线（2 子单，金额恒等式：主单四维 = Σ 子单）。
     */
    private static MasterOrder pendingMaster() {
        return new MasterOrder(MASTER_ID, "ORD202609070001", 200L,
                address(), amount(45000L, 800L, 45800L),
                List.of(SUB_A, SUB_B),
                List.of(amount(35000L, 800L, 35800L), amount(10000L, 0L, 10000L)),
                MasterOrderStatus.PENDING_PAYMENT);
    }

    /**
     * 待支付子单 A（SKU 3001 × 2 + SKU 3002 × 3）。
     */
    private static SubOrder subA() {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(35000L, 800L, 35800L),
                List.of(item(3001L, 10000L, 2), item(3002L, 5000L, 3)));
    }

    /**
     * 待支付子单 B（SKU 3101 × 1）。
     */
    private static SubOrder subB() {
        return new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002", address(),
                amount(10000L, 0L, 10000L), List.of(item(3101L, 10000L, 1)));
    }

    /**
     * 任意状态子单（已支付/已发货/已取消等非法取消面，装载构造器装配）。
     */
    private static SubOrder subWithStatus(SubOrderStatus status, Long waybillId) {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(35000L, 800L, 35800L),
                List.of(item(3001L, 10000L, 2), item(3002L, 5000L, 3)), status, waybillId);
    }

    private static OrderItem item(Long skuId, long price, int qty) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                null, null, Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight, long paid) {
        return new AmountDetail(goods, freight, 0L, paid);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /* ================= happy path ================= */

    /**
     * happy-1 待支付整单取消：逐子单状态推进 CANCELLED + 主单派生
     * CANCELLED + 子单与主单全部保存（同事务编排面，reason 不影响
     * 状态推进）。
     */
    @Test
    @DisplayName("待支付整单取消：子单取消+主单派生+全部保存")
    void cancel_pendingMaster_allCancelledAndSaved() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        orderFacade.cancel(MASTER_ID, "不想要了");

        final InOrder inOrder = inOrder(subOrderRepository, masterOrderRepository);
        inOrder.verify(subOrderRepository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        inOrder.verify(masterOrderRepository).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * happy-2 取消后聚合状态断言：子单 CANCELLED、主单派生 CANCELLED
     * （全部子单取消 → 主单已取消，派生钉死）。
     */
    @Test
    @DisplayName("取消后聚合状态：子单已取消、主单派生已取消")
    void cancel_pendingMaster_subAndMasterCancelled() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        orderFacade.cancel(MASTER_ID, null);

        // reason 可空透传不影响状态推进（子单快照与金额不动）
        final List<SubOrder> saved = List.of(subA(), subB());
        // 以装载值断言（真实聚合在 mock 仓储桩内原地推进——桩返回的同一
        // 实例即保存对象，断言其状态迁移结果）
        assertThat(subOrderRepository.getByMasterOrderId(MASTER_ID))
                .extracting(SubOrder::getStatus)
                .containsExactly(SubOrderStatus.CANCELLED, SubOrderStatus.CANCELLED);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.CANCELLED);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 子单集合为空：主单无子单为装配异常（下单保证至少一子
     * 单），防御 404 order.sub_not_found，无保存动作。
     */
    @Test
    @DisplayName("主单下无子单：防御 404，拒绝取消")
    void cancel_noSubOrders_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> orderFacade.cancel(MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /* ================= fail path ================= */

    /**
     * fail-1 主单不存在：404 order.master_not_found（编排归属校验先行，
     * 此处为契约防御面）。
     */
    @Test
    @DisplayName("主单不存在：404")
    void cancel_masterNotFound_rejected() {
        when(masterOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> orderFacade.cancel(999L, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
    }

    /**
     * fail-2 已支付子单取消：非法迁移拒绝（已支付走
     * 退款流程，不可直接取消），无保存动作。
     */
    @Test
    @DisplayName("已支付子单取消：非法迁移拒绝（走退款流程）")
    void cancel_paidSubOrder_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(
                List.of(subWithStatus(SubOrderStatus.PAID, null)));

        assertThatThrownBy(() -> orderFacade.cancel(MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-3 已发货子单取消：非法迁移拒绝（不可取消），无保存
     * 动作。
     */
    @Test
    @DisplayName("已发货子单取消：不可取消")
    void cancel_shippedSubOrder_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(
                List.of(subWithStatus(SubOrderStatus.SHIPPED, 5001L)));

        assertThatThrownBy(() -> orderFacade.cancel(MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-4 已取消子单再取消（契约防御面——编排层幂等短路先行，直接
     * 消费本实现的路径仍按非法迁移拒绝）。
     */
    @Test
    @DisplayName("已取消子单再取消：非法迁移拒绝（防御面）")
    void cancel_cancelledSubOrder_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(
                List.of(subWithStatus(SubOrderStatus.CANCELLED, null)));

        assertThatThrownBy(() -> orderFacade.cancel(MASTER_ID, "重放"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
    }

    /**
     * fail-5 部分子单非法（如 A 待支付 B 已支付——整单取消语义下混态
     * 非法）：守卫在首个非法子单处拒绝，后续子单无推进（同事务回滚面）。
     */
    @Test
    @DisplayName("混态子单：首个非法子单拒绝，整体不推进")
    void cancel_mixedSubOrders_abortAtFirstIllegal() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(
                List.of(subA(), subWithStatus(SubOrderStatus.PAID, null)));

        assertThatThrownBy(() -> orderFacade.cancel(MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /* ================= autoComplete（完成推进接线，本 WU） ================= */

    /**
     * happy-3 完成推进全链路：已发货子单标记完成（真实聚合迁移）＋主单
     * 按全部子单投影派生已完成（单子单主单）＋子单与主单保存（同事务
     * 编排面）。
     * <p>
     * 装配：getByID 与 getByMasterOrderId 桩返回同一真实实例（共享引用
     * ——推进与投影的一致性由真实聚合状态承载，与 cancel 用例同构）。
     */
    @Test
    @DisplayName("已发货子单完成：markCompleted + 主单派生已完成 + 保存")
    void autoComplete_shippedSubOrder_completedAndSaved() {
        final SubOrder shipped = subWithStatus(SubOrderStatus.SHIPPED, 5001L);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(shipped);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(shipped));

        orderFacade.autoComplete(SUB_A);

        final InOrder inOrder = inOrder(subOrderRepository, masterOrderRepository);
        // 保存的是被修改的 getByID 实例（引用同一性断言，非任意参数）
        inOrder.verify(subOrderRepository).save(shipped);
        inOrder.verify(masterOrderRepository).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
        assertThat(subOrderRepository.getByID(SUB_A).getStatus())
                .isEqualTo(SubOrderStatus.COMPLETED);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    /**
     * critical-3 <b>引用不一致回归</b>：getByID 装载的目标实例 X 与会话列表
     * （getByMasterOrderId）装载的实例 Y 非同一引用——保存段必须保存被
     * 修改的 X（而非列表项 Y），主单投影按 X 的状态派生（否则目标子单
     * 状态迁移丢失、主单派生错误）。修复前行为：保存 Y（无变更）、投影
     * 用 Y 旧状态——本用例锁死修复语义。
     */
    @Test
    @DisplayName("会话列表与目标实例不同引用：保存被修改实例 + 投影按被修改实例派生")
    void autoComplete_distinctListInstance_savesModifiedTarget() {
        final SubOrder shippedX = subWithStatus(SubOrderStatus.SHIPPED, 5001L);
        final SubOrder listY = subWithStatus(SubOrderStatus.SHIPPED, 5001L);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(shippedX);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(listY));

        orderFacade.autoComplete(SUB_A);

        verify(subOrderRepository).save(shippedX);
        verify(subOrderRepository, never()).save(listY);
        assertThat(shippedX.getStatus()).isEqualTo(SubOrderStatus.COMPLETED);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    /**
     * critical-2 多子单主单部分完成：SUB_A 完成 + SUB_B 未完成 → 主单
     * 派生部分发货（中间态正确性——主单完成须全部子单完成，派生钉死）。
     */
    @Test
    @DisplayName("多子单部分完成：主单派生部分发货（中间态）")
    void autoComplete_multiSubOrder_partialDerived() {
        final SubOrder shippedA = subWithStatus(SubOrderStatus.SHIPPED, 5001L);
        final SubOrder shippedB = new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002",
                address(), amount(10000L, 0L, 10000L),
                List.of(item(3005L, 10000L, 1)), SubOrderStatus.SHIPPED, 5002L);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(shippedA);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(shippedA, shippedB));

        orderFacade.autoComplete(SUB_A);

        assertThat(subOrderRepository.getByID(SUB_A).getStatus())
                .isEqualTo(SubOrderStatus.COMPLETED);
        assertThat(subOrderRepository.getByMasterOrderId(MASTER_ID).get(1).getStatus())
                .isEqualTo(SubOrderStatus.SHIPPED);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    /**
     * fail-6 子单不存在：404 order.sub_not_found（完成推进按子单定位，
     * 防御装配错误），无保存动作。
     */
    @Test
    @DisplayName("子单不存在：404（完成推进防御）")
    void autoComplete_subNotFound_rejected() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> orderFacade.autoComplete(999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-7 未发货子单完成（PAID）：非法迁移拒绝（仅已发货可完成——
     * 未发货直接完成/提前完成为非法），无保存动作。
     */
    @Test
    @DisplayName("未发货子单完成：非法迁移拒绝")
    void autoComplete_paidSubOrder_rejected() {
        final SubOrder paid = subWithStatus(SubOrderStatus.PAID, null);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(paid);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(paid));

        assertThatThrownBy(() -> orderFacade.autoComplete(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-8 已完成子单重复完成（契约防御面——编排层幂等短路先行，直接
     * 消费本实现的路径仍按非法迁移拒绝）。
     */
    @Test
    @DisplayName("已完成子单重复完成：非法迁移拒绝（防御面）")
    void autoComplete_completedSubOrder_rejected() {
        final SubOrder completed = subWithStatus(SubOrderStatus.COMPLETED, 5001L);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(completed);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(completed));

        assertThatThrownBy(() -> orderFacade.autoComplete(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-9 子单存在但主单缺失：404 order.master_not_found（完成推进
     * 需主单派生落库，缺失为数据异常防御），无保存动作。
     */
    @Test
    @DisplayName("子单存在但主单缺失：404（防御）")
    void autoComplete_masterMissing_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subWithStatus(SubOrderStatus.SHIPPED, 5001L));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderFacade.autoComplete(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /* ================= onPaid（支付成功推进接线，回调编排消费面） ================= */

    /**
     * happy-4 整单支付推进：待支付主单下全部子单标记已支付（真实聚合
     * 迁移）＋主单按全部子单投影派生已支付＋子单与主单全部保存
     * （回调编排同事务面：payment → order → inventory）。
     */
    @Test
    @DisplayName("成功推进：全部子单已支付 + 主单派生已支付 + 全部保存")
    void onPaid_pendingMaster_allPaidAndSaved() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        orderFacade.onPaid(MASTER_ID);

        final InOrder inOrder = inOrder(subOrderRepository, masterOrderRepository);
        inOrder.verify(subOrderRepository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        inOrder.verify(masterOrderRepository).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
        // 仓储桩内实例原地推进：断言聚合状态迁移与派生结果
        assertThat(subOrderRepository.getByMasterOrderId(MASTER_ID))
                .extracting(SubOrder::getStatus)
                .containsExactly(SubOrderStatus.PAID, SubOrderStatus.PAID);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.PAID);
    }

    /**
     * happy-5 单子单主单整单支付：唯一子单已支付 + 主单派生已支付。
     */
    @Test
    @DisplayName("单子单成功推进：子单已支付 + 主单派生已支付")
    void onPaid_singleSubOrder_paid() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subB()));

        orderFacade.onPaid(MASTER_ID);

        assertThat(subOrderRepository.getByMasterOrderId(MASTER_ID).get(0).getStatus())
                .isEqualTo(SubOrderStatus.PAID);
        assertThat(masterOrderRepository.getByID(MASTER_ID).getStatus())
                .isEqualTo(MasterOrderStatus.PAID);
    }

    /**
     * critical-3 混态推进（部分子单已支付）：逐子单推进遇已支付子单
     * 聚合守卫拒绝（子单状态非法迁移）→ 异常透传、同批保存不执行。
     */
    @Test
    @DisplayName("混态推进（部分子单已支付）：非法迁移拒绝，无保存")
    void onPaid_mixedStatus_rejected() {
        final SubOrder paidB = new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002",
                address(), amount(10000L, 0L, 10000L),
                List.of(item(3005L, 10000L, 1)), SubOrderStatus.PAID, null);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), paidB));

        assertThatThrownBy(() -> orderFacade.onPaid(MASTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * critical-4 子单集合为空：主单无子单为装配异常（下单保证至少一
     * 子单），防御 404 order.sub_not_found，无保存动作。
     */
    @Test
    @DisplayName("主单下无子单：防御 404，拒绝推进")
    void onPaid_noSubOrders_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> orderFacade.onPaid(MASTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-10 主单不存在：404 order.master_not_found（编排列装载先于
     * 推进，缺失为数据异常防御），无保存动作。
     */
    @Test
    @DisplayName("主单不存在：404（防御）")
    void onPaid_masterMissing_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderFacade.onPaid(MASTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }

    /**
     * fail-11 全部子单已支付（重复支付推进）：首个子单即命中聚合守卫
     * （仅待支付可标记支付成功）→ 非法迁移拒绝，无保存（幂等短路由
     * 回调端口支付单守卫承载，本实现保持防御面契约）。
     */
    @Test
    @DisplayName("全部子单已支付（重复推进）：非法迁移拒绝，无保存")
    void onPaid_allPaid_rejected() {
        final SubOrder paidA = subWithStatus(SubOrderStatus.PAID, null);
        final SubOrder paidB = new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002",
                address(), amount(10000L, 0L, 10000L),
                List.of(item(3005L, 10000L, 1)), SubOrderStatus.PAID, null);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(paidA, paidB));

        assertThatThrownBy(() -> orderFacade.onPaid(MASTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code());
                });
        verify(subOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(SubOrder.class));
        verify(masterOrderRepository, never()).save(org.mockito.ArgumentMatchers.any(MasterOrder.class));
    }
}