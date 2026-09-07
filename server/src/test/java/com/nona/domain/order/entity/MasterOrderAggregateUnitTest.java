package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主订单聚合结构测试：装配形态（创建/装载）、金额恒等式（主单金额摘要
 * 四维 == Σ 子单金额投影四维，TD-10）、B7.6 快照冻结语义（订单号/买家/
 * 地址/金额摘要/子单引用集合创建后不可变）、整体状态派生入口。
 * <p>
 * 结构守卫（构造路径）红阶段即实现（设计物），本类用例绿；整体状态派生
 * （deriveStatus）属 UOE 红（见 MasterOrderStatusDeriverUnitTest 红矩阵）。
 *
 * @author nona9961
 */
class MasterOrderAggregateUnitTest {

    /**
     * 测试主单主键
     */
    private static final long MASTER_ID = 6001L;

    /**
     * 测试买家账号 ID
     */
    private static final long BUYER_ID = 10001L;

    /**
     * 测试店铺 A
     */
    private static final long SHOP_A = 4001L;

    /**
     * 测试店铺 B
     */
    private static final long SHOP_B = 4002L;

    /**
     * 地址快照（六段必填）。
     */
    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 金额明细。
     */
    private static AmountDetail amount(long goods, long freight) {
        return new AmountDetail(goods, freight, 0L, goods + freight);
    }

    /**
     * 子单金额投影：店铺甲单（goods 2000 / freight 800）。
     */
    private static AmountDetail shopAAmount() {
        return amount(2000L, 800L);
    }

    /**
     * 子单金额投影：店铺乙单（goods 1000 / freight 500）。
     */
    private static AmountDetail shopBAmount() {
        return amount(1000L, 500L);
    }

    /**
     * 主单金额摘要（等于两子单四维合计）。
     */
    private static AmountDetail summary2() {
        return amount(3000L, 1300L);
    }

    /**
     * 单子单形态主单（摘要 == 该子单金额）。
     */
    private static MasterOrder master() {
        return new MasterOrder(MASTER_ID, "ORD-1", BUYER_ID, address(),
                amount(2000L, 800L), List.of(101L), List.of(shopAAmount()));
    }

    /**
     * 双子单形态主单。
     */
    private static MasterOrder master2() {
        return new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(), summary2(),
                List.of(101L, 102L), List.of(shopAAmount(), shopBAmount()));
    }

    // ---------- happy：创建与装载 ----------

    @Test
    @DisplayName("创建成功：订单号/买家/地址/金额摘要/子单引用/初始待支付齐备")
    void create_fullyAssembled() {        final MasterOrder master = master2();

        assertThat(master.getId()).isEqualTo(MASTER_ID);
        assertThat(master.getOrderNo()).isEqualTo("ORD-2");
        assertThat(master.getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(master.getAddress().getRecipient()).isEqualTo("张三");
        assertThat(master.getAmount().getGoodsAmount()).isEqualTo(3000L);
        assertThat(master.getAmount().getFreightAmount()).isEqualTo(1300L);
        assertThat(master.getAmount().getPaidAmount()).isEqualTo(4300L);
        assertThat(master.getSubOrderIds()).containsExactly(101L, 102L);
        assertThat(master.getStatus()).isEqualTo(MasterOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("单子单创建（未拆单形态）")
    void create_singleSub() {
        final MasterOrder master = master();
        assertThat(master.getSubOrderIds()).containsExactly(101L);
        assertThat(master.getAmount().getPaidAmount()).isEqualTo(2800L);
    }

    @Test
    @DisplayName("装载恢复：持久化整体状态与子单引用还原")
    void load_restoresStatusAndSubs() {
        final MasterOrder master = new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), List.of(101L, 102L), List.of(), MasterOrderStatus.PARTIALLY_SHIPPED);

        assertThat(master.getStatus()).isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
        assertThat(master.getSubOrderIds()).containsExactly(101L, 102L);
    }

    // ---------- critical：恒等式边界与不可变视图 ----------

    @Test
    @DisplayName("金额恒等边界：单子单金额摘要 == 该子单金额")
    void create_summaryEqualsSingleSub() {
        final MasterOrder master = master();
        assertThat(master.getAmount().getGoodsAmount()).isEqualTo(2000L);
        assertThat(master.getAmount().getFreightAmount()).isEqualTo(800L);
    }

    @Test
    @DisplayName("金额恒等：三字段合计（商品额/运费/实付）各自等于 Σ 子单")
    void create_summaryEqualsSumOfSubs() {
        final MasterOrder master = new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), List.of(101L, 102L), List.of(shopAAmount(), shopBAmount()));
        assertThat(master.getAmount().getGoodsAmount()).isEqualTo(3000L);
        assertThat(master.getAmount().getFreightAmount()).isEqualTo(1300L);
        assertThat(master.getAmount().getPaidAmount()).isEqualTo(4300L);
    }

    @Test
    @DisplayName("子单引用集合冻结：构造后修改传入列表不影响聚合")
    void subOrderIds_frozenFromExternalMutation() {
        final List<Long> ids = new ArrayList<>(List.of(101L, 102L));
        final MasterOrder master = new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), ids, List.of(shopAAmount(), shopBAmount()));

        ids.clear();

        assertThat(master.getSubOrderIds()).containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("子单引用读取视图不可变")
    void subOrderIds_readViewImmutable() {
        final MasterOrder master = master2();
        assertThatThrownBy(() -> master.getSubOrderIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("快照冻结：订单号/买家/地址/金额/引用全部 final")
    void snapshotFields_allFinal() throws Exception {
        final Field orderNo = MasterOrder.class.getDeclaredField("orderNo");
        final Field buyerId = MasterOrder.class.getDeclaredField("buyerId");
        final Field address = MasterOrder.class.getDeclaredField("address");
        final Field amount = MasterOrder.class.getDeclaredField("amount");
        final Field subOrderIds = MasterOrder.class.getDeclaredField("subOrderIds");

        assertThat(Modifier.isFinal(orderNo.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(buyerId.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(address.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(amount.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(subOrderIds.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("冻结语义：外部无任何 setter")
    void noSetterExposed() {
        for (final Method method : MasterOrder.class.getDeclaredMethods()) {
            assertThat(method.getName()).doesNotStartWith("set");
        }
    }

    // ---------- fail：装配守卫拒绝 ----------

    @Test
    @DisplayName("无子单拒绝（order.sub_empty：跨店拆单必得至少一子单）")
    void create_noSubs_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), List.of(), List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_EMPTY.code());
    }

    @Test
    @DisplayName("金额不自洽拒绝：商品总额维度不符（order.amount_mismatch）")
    void create_goodsMismatch_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                amount(2999L, 1300L), List.of(101L, 102L),
                List.of(shopAAmount(), shopBAmount())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_AMOUNT_MISMATCH.code());
    }

    @Test
    @DisplayName("金额不自洽拒绝：运费维度不符")
    void create_freightMismatch_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                amount(3000L, 1301L), List.of(101L, 102L),
                List.of(shopAAmount(), shopBAmount())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_AMOUNT_MISMATCH.code());
    }

    @Test
    @DisplayName("金额不自洽拒绝：实付维度不符（子单恒等式各自成立但合计不符）")
    void create_paidMismatch_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                amount(3000L, 1300L), List.of(101L, 102L),
                List.of(shopAAmount(), amount(1000L, 501L))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_AMOUNT_MISMATCH.code());
    }

    @Test
    @DisplayName("订单号空白拒绝")
    void create_blankOrderNo_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "  ", BUYER_ID, address(),
                summary2(), List.of(101L), List.of(shopAAmount())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("买家缺失拒绝")
    void create_buyerMissing_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", null, address(),
                summary2(), List.of(101L), List.of(shopAAmount())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("地址/金额摘要/子单引用缺失拒绝")
    void create_missingSnapshot_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, null,
                summary2(), List.of(101L), List.of(shopAAmount())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                null, List.of(101L), List.of(shopAAmount())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("装载状态缺失拒绝（持久化脏数据防御）")
    void load_missingStatus_rejected() {
        assertThatThrownBy(() -> new MasterOrder(MASTER_ID, "ORD-2", BUYER_ID, address(),
                summary2(), List.of(101L), List.of(), null))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 派生行为（红：实现体 UOE，语义按最终契约） ----------

    @Test
    @DisplayName("整体状态派生：全部子单已支付投影 → 主单已支付")
    void deriveStatus_allPaid_updatesStatus() {
        final MasterOrder master = master2();
        final MasterOrderStatus derived = master.deriveStatus(
                List.of(SubOrderStatus.PAID, SubOrderStatus.PAID));
        assertThat(derived).isEqualTo(MasterOrderStatus.PAID);
        assertThat(master.getStatus()).isEqualTo(MasterOrderStatus.PAID);
    }

    @Test
    @DisplayName("整体状态派生：跨店部分发货投影 → 主单部分发货")
    void deriveStatus_partiallyShipped_updatesStatus() {
        final MasterOrder master = master2();
        final MasterOrderStatus derived = master.deriveStatus(
                List.of(SubOrderStatus.PAID, SubOrderStatus.SHIPPED));
        assertThat(derived).isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
        assertThat(master.getStatus()).isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }
}