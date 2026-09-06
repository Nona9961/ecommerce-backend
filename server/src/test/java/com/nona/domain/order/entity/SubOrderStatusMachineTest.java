package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 子订单状态机测试：迁移守卫收敛在聚合方法——happy（正常迁移全链：
 * 支付/发货/完成/取消/退款/超时关单）、critical（归属校验通过/运单
 * 必备/触发源共用迁移）、fail（重复迁移、前置不满足、跨店铺发货、终态
 * 不可逆等非法迁移拒绝）。
 * <p>
 * 红阶段：聚合迁移方法实现体为 UnsupportedOperationException——happy
 * 用例因 UOE 红（Error），fail 用例「期望 BusinessException 实得 UOE」
 * 红（Failure），红因均为实现缺失；测试语义按最终迁移契约书写（绿阶段
 * 实现后无需改写）。状态前置经装载构造器直接装配（装载路径不做写校验）。
 *
 * @author nona9961
 */
class SubOrderStatusMachineTest {

    /**
     * 测试子单主键
     */
    private static final long SUB_ID = 5001L;

    /**
     * 测试归属主单 ID
     */
    private static final long MASTER_ID = 6001L;

    /**
     * 测试店铺 A（子单归属店铺）
     */
    private static final long SHOP_A = 4001L;

    /**
     * 测试店铺 B（越权操作者店铺）
     */
    private static final long SHOP_B = 4002L;

    /**
     * 测试运单 ID
     */
    private static final long WAYBILL_ID = 7001L;

    /**
     * 构造指定状态的子单（装载构造器装配，跳过写路径校验）。
     *
     * @param status    目标状态
     * @param waybillId 运单引用（可空）
     * @return 子单
     */
    private static SubOrder subOrder(SubOrderStatus status, Long waybillId) {
        return new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1", TestSnapshot.address(),
                TestSnapshot.amount(2000L, 800L), TestSnapshot.items2(),
                status, waybillId);
    }

    // ---------- happy：正常迁移 ----------

    @Test
    @DisplayName("支付推进：待支付 → 已支付")
    void markPaid_fromPending_paid() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        sub.markPaid();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.PAID);
    }

    @Test
    @DisplayName("发货推进：已支付 → 已发货（本店操作 + 运单定型）")
    void markShipped_fromPaid_shipped() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        sub.markShipped(SHOP_A, WAYBILL_ID);
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.SHIPPED);
        assertThat(sub.getWaybillId()).isEqualTo(WAYBILL_ID);
    }

    @Test
    @DisplayName("完成推进：已发货 → 已完成（确认收货与超时自动完成共用迁移）")
    void markCompleted_fromShipped_completed() {
        final SubOrder sub = subOrder(SubOrderStatus.SHIPPED, WAYBILL_ID);
        sub.markCompleted();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("待支付直接取消：待支付 → 已取消（主动取消与支付超时共用迁移）")
    void cancel_fromPending_cancelled() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        sub.cancel();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("主动退款全链：已支付 → 退款中 → 已退款")
    void refund_fromPaid_refunded() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        sub.markRefunding();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.REFUNDING);
        sub.markRefunded();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("已发货可申请退款（B8.4 部分状态可申请）")
    void markRefunding_fromShipped_refunding() {
        final SubOrder sub = subOrder(SubOrderStatus.SHIPPED, WAYBILL_ID);
        sub.markRefunding();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("已完成可申请退款（全阶段可退）")
    void markRefunding_fromCompleted_refunding() {
        final SubOrder sub = subOrder(SubOrderStatus.COMPLETED, WAYBILL_ID);
        sub.markRefunding();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("发货超时关单：已支付 → 已关闭（B9.4②）")
    void closeByTimeout_fromPaid_closed() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        sub.closeByTimeout();
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.CLOSED);
    }

    // ---------- critical：守卫的通过侧边界 ----------

    @Test
    @DisplayName("归属校验：操作者店铺 == 子单店铺时发货放行")
    void markShipped_ownShop_allowed() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        sub.markShipped(SHOP_A, WAYBILL_ID);
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.SHIPPED);
    }

    // ---------- fail：非法迁移拒绝 ----------

    @Test
    @DisplayName("重复支付拒绝（支付回调幂等防线之外的聚合守卫）")
    void markPaid_fromPaid_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        final BusinessException ex = catchThrowableOfType(sub::markPaid, BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getBusinessCode()).isEqualTo("order.sub_status_illegal");
    }

    @Test
    @DisplayName("未支付发货拒绝")
    void markShipped_fromPending_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        assertThatThrownBy(() -> sub.markShipped(SHOP_A, WAYBILL_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重复发货拒绝（一子单一在途运单语义）")
    void markShipped_fromShipped_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.SHIPPED, WAYBILL_ID);
        assertThatThrownBy(() -> sub.markShipped(SHOP_A, WAYBILL_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("发货缺少运单拒绝")
    void markShipped_withoutWaybill_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        assertThatThrownBy(() -> sub.markShipped(SHOP_A, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("未发货直接完成拒绝")
    void markCompleted_fromPending_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        assertThatThrownBy(sub::markCompleted)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重复完成拒绝")
    void markCompleted_fromCompleted_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.COMPLETED, WAYBILL_ID);
        assertThatThrownBy(sub::markCompleted)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("已支付直接取消拒绝（已支付取消走退款流程）")
    void cancel_fromPaid_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        assertThatThrownBy(sub::cancel)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("已发货取消拒绝（B8.6 ③ 已发货后不可取消）")
    void cancel_fromShipped_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.SHIPPED, WAYBILL_ID);
        assertThatThrownBy(sub::cancel)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("跨店铺发货拒绝（归属校验：B 店操作 A 店子单）")
    void markShipped_otherShop_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        final BusinessException ex = catchThrowableOfType(
                () -> sub.markShipped(SHOP_B, WAYBILL_ID), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getBusinessCode()).isEqualTo("order.sub_shop_mismatch");
    }

    @Test
    @DisplayName("未支付退款申请拒绝")
    void markRefunding_fromPending_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        assertThatThrownBy(sub::markRefunding)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重复退款申请拒绝（退款中再退款）")
    void markRefunding_fromRefunding_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.REFUNDING, null);
        assertThatThrownBy(sub::markRefunding)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("非退款中直接退款成功拒绝")
    void markRefunded_fromPaid_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PAID, null);
        assertThatThrownBy(sub::markRefunded)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("待支付直接退款成功拒绝")
    void markRefunded_fromPending_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        assertThatThrownBy(sub::markRefunded)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("未支付关单拒绝（支付超时走取消，发货超时仅适用已支付）")
    void closeByTimeout_fromPending_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.PENDING_PAYMENT, null);
        assertThatThrownBy(sub::closeByTimeout)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重复关单拒绝")
    void closeByTimeout_fromClosed_rejected() {
        final SubOrder sub = subOrder(SubOrderStatus.CLOSED, null);
        assertThatThrownBy(sub::closeByTimeout)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("终态不可逆：已取消后任何迁移拒绝")
    void cancelled_isTerminal() {
        final SubOrder sub = subOrder(SubOrderStatus.CANCELLED, null);
        assertThatThrownBy(sub::markPaid).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sub.markShipped(SHOP_A, WAYBILL_ID))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markCompleted).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markRefunding).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("终态不可逆：已退款后任何迁移拒绝")
    void refunded_isTerminal() {
        final SubOrder sub = subOrder(SubOrderStatus.REFUNDED, null);
        assertThatThrownBy(sub::markPaid).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markRefunded).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markCompleted).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("终态不可逆：已关闭后任何迁移拒绝")
    void closed_isTerminal() {
        final SubOrder sub = subOrder(SubOrderStatus.CLOSED, null);
        assertThatThrownBy(sub::markPaid).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markCompleted).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markRefunding).isInstanceOf(BusinessException.class);
        assertThatThrownBy(sub::markRefunded).isInstanceOf(BusinessException.class);
    }

    /**
     * 测试快照装配辅助（本测试类专用静态内部，隔离辅助方法）。
     */
    static final class TestSnapshot {

        private TestSnapshot() {
        }

        /**
         * 地址快照。
         *
         * @return 地址快照
         */
        static AddressSnapshot address() {
            return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
        }

        /**
         * 金额明细（商品额必须与 items 小计合计一致）。
         *
         * @param goods 商品总额（分）
         * @param freight 运费（分）
         * @return 金额明细
         */
        static AmountDetail amount(long goods, long freight) {
            return new AmountDetail(goods, freight, 0L, goods + freight);
        }

        /**
         * 两个订单项（小计合计 2000 分：100×10 + 200×5）。
         *
         * @return 订单项列表
         */
        static List<OrderItem> items2() {
            return List.of(
                    new OrderItem(2001L, 3001L, "商品甲", 100L, 10, 1000L, null, null, null, null),
                    new OrderItem(2002L, 3002L, "商品乙", 200L, 5, 1000L, null, null, null, null));
        }
    }
}