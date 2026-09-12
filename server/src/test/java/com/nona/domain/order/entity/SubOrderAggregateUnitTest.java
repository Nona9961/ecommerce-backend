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
 * 子订单聚合结构测试：装配形态（创建/装载）、金额自洽恒等式（商品总额
 * == Σ 订单项小计）、快照冻结语义（地址/金额/订单项创建后不可变）、
 * 归属定型（店铺/主单引用创建后不可变）。
 * <p>
 * 结构守卫（构造路径）为设计物实现（本类用例绿）；状态机行为
 * 契约见 SubOrderStatusMachineUnitTest 矩阵。
 *
 * @author nona9961
 */
class SubOrderAggregateUnitTest {

    /**
     * 测试子单主键
     */
    private static final long SUB_ID = 5001L;

    /**
     * 测试归属主单 ID
     */
    private static final long MASTER_ID = 6001L;

    /**
     * 测试店铺 A
     */
    private static final long SHOP_A = 4001L;

    /**
     * 测试店铺 B
     */
    private static final long SHOP_B = 4002L;

    /**
     * 测试运单 ID
     */
    private static final long WAYBILL_ID = 7001L;

    /**
     * 地址快照（六段必填）。
     */
    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 金额明细（goods 须与 items 小计合计一致）。
     */
    private static AmountDetail amount(long goods, long freight) {
        return new AmountDetail(goods, freight, 0L, goods + freight);
    }

    /**
     * 订单项（小计合计 2000 分）。
     */
    private static List<OrderItem> items() {
        return List.of(
                new OrderItem(2001L, 3001L, "商品甲", 100L, 10, 1000L, null, null, null, null),
                new OrderItem(2002L, 3002L, "商品乙", 200L, 5, 1000L, null, null, null, null));
    }

    /**
     * 创建形态子单。
     */
    private static SubOrder subOrder() {
        return new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1", address(), amount(2000L, 800L), items());
    }

    // ---------- happy：创建与装载 ----------

    @Test
    @DisplayName("创建成功：归属/单号/地址/金额/条目/初始态齐备")
    void create_fullyAssembled() {
        final SubOrder sub = subOrder();

        assertThat(sub.getId()).isEqualTo(SUB_ID);
        assertThat(sub.getMasterOrderId()).isEqualTo(MASTER_ID);
        assertThat(sub.getShopId()).isEqualTo(SHOP_A);
        assertThat(sub.getSubOrderNo()).isEqualTo("SUB-NO-1");
        assertThat(sub.getAddress().getRecipient()).isEqualTo("张三");
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(2000L);
        assertThat(sub.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(sub.getAmount().getPaidAmount()).isEqualTo(2800L);
        assertThat(sub.getItems()).hasSize(2);
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.PENDING_PAYMENT);
        assertThat(sub.getWaybillId()).isNull();
    }

    @Test
    @DisplayName("装载恢复：持久化状态与运单引用完整还原")
    void load_restoresStatusAndWaybill() {
        final SubOrder sub = new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 800L), items(),
                SubOrderStatus.SHIPPED, WAYBILL_ID);

        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.SHIPPED);
        assertThat(sub.getWaybillId()).isEqualTo(WAYBILL_ID);
    }

    @Test
    @DisplayName("单条目子单装载（单店单 SKU 下单形态）")
    void load_singleItem() {
        final List<OrderItem> single = List.of(
                new OrderItem(2001L, 3001L, "商品甲", 100L, 10, 1000L, null, null, null, null));
        final SubOrder sub = new SubOrder(SUB_ID, MASTER_ID, SHOP_B, "SUB-NO-2",
                address(), amount(1000L, 500L), single);

        assertThat(sub.getItems()).hasSize(1);
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(1000L);
    }

    // ---------- critical：恒等式边界与不可变视图 ----------

    @Test
    @DisplayName("金额自洽边界：商品总额恰等于 Σ 条目小计（多条目混合价位）")
    void create_amountMatchesItems_ok() {
        final SubOrder sub = new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 0L), items());
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("条目集合冻结：构造后修改传入列表不影响聚合（副本装配）")
    void items_frozenFromExternalMutation() {
        final List<OrderItem> mutable = new ArrayList<>(items());
        final SubOrder sub = new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 800L), mutable);

        mutable.clear();

        assertThat(sub.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("条目读取视图不可变：外部修改读取结果被拒")
    void items_readViewImmutable() {
        final SubOrder sub = subOrder();
        assertThatThrownBy(() -> sub.getItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("快照冻结：身份/归属/快照字段全部 final（无变更路径）")
    void snapshotFields_allFinal() throws Exception {
        final Field id = SubOrder.class.getDeclaredField("id");
        final Field masterOrderId = SubOrder.class.getDeclaredField("masterOrderId");
        final Field shopId = SubOrder.class.getDeclaredField("shopId");
        final Field subOrderNo = SubOrder.class.getDeclaredField("subOrderNo");
        final Field address = SubOrder.class.getDeclaredField("address");
        final Field amount = SubOrder.class.getDeclaredField("amount");
        final Field items = SubOrder.class.getDeclaredField("items");

        assertThat(Modifier.isFinal(id.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(masterOrderId.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(shopId.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(subOrderNo.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(address.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(amount.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(items.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("冻结语义：外部无任何 setter（字段变更无包外路径）")
    void noSetterExposed() {
        for (final Method method : SubOrder.class.getDeclaredMethods()) {
            assertThat(method.getName()).doesNotStartWith("set");
        }
    }

    // ---------- fail：装配守卫拒绝 ----------

    @Test
    @DisplayName("空条目拒绝（order.sub_empty：空子单无业务意义）")
    void create_emptyItems_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(0L, 0L), List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_EMPTY.code());
    }

    @Test
    @DisplayName("金额不自洽拒绝：商品总额与 Σ 条目小计不符（order.sub_amount_mismatch）")
    void create_amountMismatch_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(999L, 800L), items()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_AMOUNT_MISMATCH.code());
    }

    @Test
    @DisplayName("归属店铺缺失拒绝")
    void create_shopMissing_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, null, "SUB-NO-1",
                address(), amount(2000L, 800L), items()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("主单引用缺失拒绝")
    void create_masterMissing_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, null, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 800L), items()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("单号空白拒绝")
    void create_blankSubOrderNo_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "  ",
                address(), amount(2000L, 800L), items()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("地址/金额/条目缺失拒绝")
    void create_missingSnapshot_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                null, amount(2000L, 800L), items()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), null, items()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 800L), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("装载状态缺失拒绝（持久化脏数据防御）")
    void load_missingStatus_rejected() {
        assertThatThrownBy(() -> new SubOrder(SUB_ID, MASTER_ID, SHOP_A, "SUB-NO-1",
                address(), amount(2000L, 800L), items(), null, null))
                .isInstanceOf(BusinessException.class);
    }
}