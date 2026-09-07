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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单门面退款面契约测试（OrderFacade 退款面三成员：beginRefund /
 * completeRefund / closeByTimeout——红阶段 UOE 契约，绿阶段按本矩阵
 * 目标行为实现后原样转绿）。
 * <p>
 * 覆盖：happy——申请推进（退款中 + 主单派生）、成功推进（已退款 + 主单
 * 派生）、超时关单推进（已关闭 + 主单派生）；critical——已关闭子单退款
 * 成功幂等跳过（发货超时路径，资金侧由退款单承载）；fail——子单不存在
 * 404、非法状态迁移守卫（未支付申请退款 / 非退款中标记成功 / 非已支付
 * 超时关单）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class OrderFacadeRefundContractUnitTest {

    /**
     * 测试主单 / 子单 / 店铺 / SKU
     */
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SHOP_A = 4001L;
    private static final long SKU_A1 = 3001L;

    @Mock
    private MasterOrderRepository masterOrderRepository;

    @Mock
    private SubOrderRepository subOrderRepository;

    /**
     * 被测订单门面（红阶段不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private OrderFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new OrderFacadeImpl(masterOrderRepository, subOrderRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 主单基线（3 子单投影占位：目标子单 + 其余已支付）——派生断言面
     * 按主单装配路径推进后刷新。
     */
    private static MasterOrder master(MasterOrderStatus status) {
        return new MasterOrder(MASTER_ID, "ORD202609070001", 200L,
                address(), amount(45000L, 800L, 45800L),
                List.of(SUB_A), List.of(amount(45000L, 800L, 45800L)), status);
    }

    /**
     * 按状态装配合法子单（单 SKU：SKU_A1 × 2）。
     */
    private static SubOrder subA(SubOrderStatus status) {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(45000L, 800L, 45800L),
                List.of(item(SKU_A1, 22500L, 2)), status, null);
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
     * happy-1 beginRefund：目标子单 已支付 → 退款中 + 主单按子单投影派生
     * （任一退款中 → 主单退款中）+ 子单与主单同批保存。
     */
    @Test
    @DisplayName("退款申请推进：子单退款中 + 主单派生退款中 + 同批保存")
    void beginRefund_paidSubOrder_movesToRefunding() {
        final SubOrder subOrder = subA(SubOrderStatus.PAID);
        final MasterOrder masterOrder = master(MasterOrderStatus.REFUNDING);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOrder);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subOrder));

        facade.beginRefund(SUB_A);

        assertThat(subOrder.getStatus()).isEqualTo(SubOrderStatus.REFUNDING);
        assertThat(masterOrder.getStatus()).isEqualTo(MasterOrderStatus.REFUNDING);
        verify(subOrderRepository).save(subOrder);
        verify(masterOrderRepository).save(masterOrder);
    }

    /**
     * happy-2 completeRefund：目标子单 退款中 → 已退款（终态）+ 主单按
     * 子单投影派生（全部已退款 → 主单已退款）+ 同批保存。
     */
    @Test
    @DisplayName("退款成功推进：子单已退款 + 主单派生已退款 + 同批保存")
    void completeRefund_refundingSubOrder_movesToRefunded() {
        final SubOrder subOrder = subA(SubOrderStatus.REFUNDING);
        final MasterOrder masterOrder = master(MasterOrderStatus.REFUNDED);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOrder);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subOrder));

        facade.completeRefund(SUB_A);

        assertThat(subOrder.getStatus()).isEqualTo(SubOrderStatus.REFUNDED);
        assertThat(masterOrder.getStatus()).isEqualTo(MasterOrderStatus.REFUNDED);
        verify(subOrderRepository).save(subOrder);
        verify(masterOrderRepository).save(masterOrder);
    }

    /**
     * happy-3 closeByTimeout：目标子单 已支付 → 已关闭（终态，B9.4②）+
     * 主单按子单投影派生（全部已关闭 → 主单已关闭）+ 同批保存。
     */
    @Test
    @DisplayName("发货超时关单推进：子单已关闭 + 主单派生已关闭 + 同批保存")
    void closeByTimeout_paidSubOrder_movesToClosed() {
        final SubOrder subOrder = subA(SubOrderStatus.PAID);
        final MasterOrder masterOrder = master(MasterOrderStatus.CLOSED);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOrder);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subOrder));

        facade.closeByTimeout(SUB_A);

        assertThat(subOrder.getStatus()).isEqualTo(SubOrderStatus.CLOSED);
        assertThat(masterOrder.getStatus()).isEqualTo(MasterOrderStatus.CLOSED);
        verify(subOrderRepository).save(subOrder);
        verify(masterOrderRepository).save(masterOrder);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已关闭子单退款成功幂等跳过：发货超时关单退款路径——履约
     * 侧终态定格（领域模型明示 ship-timeout 子单终态 CLOSED 而非
     * REFUNDED），资金侧已退款由退款单承载——不迁移不落库。
     */
    @Test
    @DisplayName("已关闭子单退款成功：幂等跳过（发货超时路径），不迁移不落库")
    void completeRefund_closedSubOrder_idempotentSkip() {
        final SubOrder subOrder = subA(SubOrderStatus.CLOSED);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);

        facade.completeRefund(SUB_A);

        assertThat(subOrder.getStatus()).isEqualTo(SubOrderStatus.CLOSED);
        verify(subOrderRepository, never()).save(any());
        verify(masterOrderRepository, never()).save(any());
    }

    /* ================= fail path ================= */

    /**
     * fail-1 子单不存在：404 契约防御（编排层归属校验先行，此处为装载
     * 防御面）。
     */
    @Test
    @DisplayName("子单不存在：sub_not_found 404")
    void beginRefund_subNotFound_rejected() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> facade.beginRefund(999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(subOrderRepository, never()).save(any());
    }

    /**
     * fail-2 未支付子单申请退款：聚合守卫拒绝（order.sub_status_illegal，
     * B8.4① 仅已支付/已发货/已完成可申请）。
     */
    @Test
    @DisplayName("待支付子单申请退款：聚合守卫拒绝 sub_status_illegal")
    void beginRefund_pendingPayment_rejected() {
        final SubOrder subOrder = subA(SubOrderStatus.PENDING_PAYMENT);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master(MasterOrderStatus.PENDING_PAYMENT));
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subOrder));

        assertThatThrownBy(() -> facade.beginRefund(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));
        verify(subOrderRepository, never()).save(any());
    }

    /**
     * fail-3 非退款中标记退款成功：聚合守卫拒绝（order.sub_status_illegal
     * ——未退款流程的子单属数据异常防御）。
     */
    @Test
    @DisplayName("已支付子单标记退款成功：聚合守卫拒绝 sub_status_illegal")
    void completeRefund_nonRefunding_rejected() {
        final SubOrder subOrder = subA(SubOrderStatus.PAID);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);

        assertThatThrownBy(() -> facade.completeRefund(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));
        verify(subOrderRepository, never()).save(any());
    }

    /**
     * fail-4 非已支付超时关单：聚合守卫拒绝（order.sub_status_illegal——
     * 支付超时走取消路径，已发货/已完成不适用超时关单；重复关闭拒绝）。
     */
    @Test
    @DisplayName("已发货子单超时关单：聚合守卫拒绝 sub_status_illegal")
    void closeByTimeout_nonPaid_rejected() {
        final SubOrder subOrder = subA(SubOrderStatus.SHIPPED);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subOrder);
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master(MasterOrderStatus.SHIPPED));
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subOrder));

        assertThatThrownBy(() -> facade.closeByTimeout(SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));
        verify(subOrderRepository, never()).save(any());
    }
}