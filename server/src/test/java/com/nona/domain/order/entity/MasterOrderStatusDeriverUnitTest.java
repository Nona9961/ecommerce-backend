package com.nona.domain.order.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主单状态派生器纯函数测试：主单整体状态 = Σ 子单状态的派生规则矩阵钉死
 * （全部取消→取消；任一发货→部分发货…）——覆盖旁路覆盖/
 * 主链派生/旁路混合兜底/防御性拒绝全部分支，防止派生规则漂移。
 * <p>
 * 派生规则按最终契约书写（实现后无需改写）。
 *
 * @author nona9961
 */
class MasterOrderStatusDeriverUnitTest {

    /**
     * 状态投影辅助：按给定状态构造输入列表。
     *
     * @param statuses 子单状态（可变参数）
     * @return 投影列表
     */
    private static List<SubOrderStatus> of(SubOrderStatus... statuses) {
        return List.of(statuses);
    }

    // ---------- happy：单子单恒等映射 ----------

    @Test
    @DisplayName("单子单：待支付 → 主单待支付")
    void derive_singlePending_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.PENDING_PAYMENT)))
                .isEqualTo(MasterOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("单子单：已支付 → 主单已支付")
    void derive_singlePaid_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.PAID)))
                .isEqualTo(MasterOrderStatus.PAID);
    }

    @Test
    @DisplayName("单子单：已发货 → 主单已发货")
    void derive_singleShipped_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.SHIPPED)))
                .isEqualTo(MasterOrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("单子单：已完成 → 主单已完成")
    void derive_singleCompleted_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("单子单：已取消 → 主单已取消")
    void derive_singleCancelled_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.CANCELLED)))
                .isEqualTo(MasterOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("单子单：退款中 → 主单退款中")
    void derive_singleRefunding_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.REFUNDING)))
                .isEqualTo(MasterOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("单子单：已退款 → 主单已退款")
    void derive_singleRefunded_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.REFUNDED)))
                .isEqualTo(MasterOrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("单子单：已关闭 → 主单已关闭")
    void derive_singleClosed_maps() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.CLOSED)))
                .isEqualTo(MasterOrderStatus.CLOSED);
    }

    // ---------- happy：全一致多子单 ----------

    @Test
    @DisplayName("全一致：两子单均已支付 → 主单已支付")
    void derive_allPaid_sameAsSingle() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.PAID, SubOrderStatus.PAID)))
                .isEqualTo(MasterOrderStatus.PAID);
    }

    @Test
    @DisplayName("全一致：两子单均已完成 → 主单已完成")
    void derive_allCompleted_sameAsSingle() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.COMPLETED, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("全一致：两子单均已取消 → 主单已取消（全部取消→主单取消）")
    void derive_allCancelled_masterCancelled() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.CANCELLED, SubOrderStatus.CANCELLED)))
                .isEqualTo(MasterOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("全一致：两子单均已关闭 → 主单已关闭")
    void derive_allClosed_masterClosed() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.CLOSED, SubOrderStatus.CLOSED)))
                .isEqualTo(MasterOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("全一致：两子单均已退款 → 主单已退款")
    void derive_allRefunded_masterRefunded() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDED, SubOrderStatus.REFUNDED)))
                .isEqualTo(MasterOrderStatus.REFUNDED);
    }

    // ---------- critical：部分发货 / 退款中优先 / 旁路混合 ----------

    @Test
    @DisplayName("部分发货：已支付 + 已发货 → 主单部分发货")
    void derive_paidPlusShipped_partiallyShipped() {
        assertThat(MasterOrderStatusDeriver.derive(of(SubOrderStatus.PAID, SubOrderStatus.SHIPPED)))
                .isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("部分发货：已支付 + 已完成 → 主单部分发货")
    void derive_paidPlusCompleted_partiallyShipped() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.PAID, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("部分发货：已发货 + 已完成（未全完成）→ 主单部分发货")
    void derive_shippedPlusCompleted_partiallyShipped() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.SHIPPED, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("部分发货：跨店三子单（支付+发货+完成）→ 主单部分发货")
    void derive_threeMixed_partiallyShipped() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.PAID, SubOrderStatus.SHIPPED, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("退款中优先：退款中 + 已完成 → 主单退款中（在途资金显性优先）")
    void derive_refundingPlusCompleted_masterRefunding() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDING, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("退款中优先：退款中 + 已退款 → 主单退款中")
    void derive_refundingPlusRefunded_masterRefunding() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED)))
                .isEqualTo(MasterOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("旁路混合：已退款 + 已完成 → 主单已完成（剔除旁路后主链派生）")
    void derive_refundedPlusCompleted_masterCompleted() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDED, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("旁路混合：已退款 + 已发货 → 主单已发货")
    void derive_refundedPlusShipped_masterShipped() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDED, SubOrderStatus.SHIPPED)))
                .isEqualTo(MasterOrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("旁路混合：已退款 + 已支付 → 主单已支付")
    void derive_refundedPlusPaid_masterPaid() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDED, SubOrderStatus.PAID)))
                .isEqualTo(MasterOrderStatus.PAID);
    }

    @Test
    @DisplayName("旁路混合：已关闭 + 已完成 → 主单已完成")
    void derive_closedPlusCompleted_masterCompleted() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.CLOSED, SubOrderStatus.COMPLETED)))
                .isEqualTo(MasterOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("旁路混合：已关闭 + 已支付 → 主单已支付（无发货主链成员）")
    void derive_closedPlusPaid_masterPaid() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.CLOSED, SubOrderStatus.PAID)))
                .isEqualTo(MasterOrderStatus.PAID);
    }

    @Test
    @DisplayName("旁路混合：已关闭 + 已退款 → 主单已退款（钱已退优先展示）")
    void derive_closedPlusRefunded_masterRefunded() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.CLOSED, SubOrderStatus.REFUNDED)))
                .isEqualTo(MasterOrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("跨店典型：退款 + 发货 + 支付 → 主单已发货（剔除旁路后主链）")
    void derive_refundedShippedPaid_masterShipped() {
        assertThat(MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.REFUNDED, SubOrderStatus.SHIPPED, SubOrderStatus.PAID)))
                .isEqualTo(MasterOrderStatus.SHIPPED);
    }

    // ---------- fail：防御性拒绝 ----------

    @Test
    @DisplayName("空投影列表拒绝（主单必含子单）")
    void derive_emptyList_rejected() {
        assertThatThrownBy(() -> MasterOrderStatusDeriver.derive(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 投影拒绝")
    void derive_nullList_rejected() {
        assertThatThrownBy(() -> MasterOrderStatusDeriver.derive(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("待支付混合拒绝（整单支付语义：不可达组合）")
    void derive_pendingMixed_rejected() {
        assertThatThrownBy(() -> MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.PENDING_PAYMENT, SubOrderStatus.PAID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("待支付与完成混合拒绝（不可达组合）")
    void derive_pendingAndCompleted_rejected() {
        assertThatThrownBy(() -> MasterOrderStatusDeriver.derive(
                of(SubOrderStatus.PENDING_PAYMENT, SubOrderStatus.COMPLETED)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}