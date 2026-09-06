package com.nona.domain.order;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.MasterOrderStatusDeriver;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.factory.MasterOrderFactory;
import com.nona.domain.order.factory.SubOrderFactory;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.persistence.BaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单域契约钉（红阶段即绿）：状态值定型集合、聚合/工厂/仓储/门面签名
 * 与业务码（只增不改）防漂移——实现期签名、码值、枚举集合改动即失败，
 * 消费编排（支付回调/发货/超时）按本契约装配。
 *
 * @author nona9961
 */
class OrderContractTest {

    // ---------- 状态值定型 ----------

    @Test
    @DisplayName("子单状态值定型：8 值精确集合")
    void subOrderStatus_valuesFrozen() {
        assertThat(SubOrderStatus.values()).containsExactlyInAnyOrder(
                SubOrderStatus.PENDING_PAYMENT, SubOrderStatus.PAID,
                SubOrderStatus.SHIPPED, SubOrderStatus.COMPLETED,
                SubOrderStatus.CANCELLED, SubOrderStatus.REFUNDING,
                SubOrderStatus.REFUNDED, SubOrderStatus.CLOSED);
        assertThat(SubOrderStatus.values()).hasSize(8);
    }

    @Test
    @DisplayName("主单状态值定型：9 值精确集合（含派生值部分发货）")
    void masterOrderStatus_valuesFrozen() {
        assertThat(MasterOrderStatus.values()).containsExactlyInAnyOrder(
                MasterOrderStatus.PENDING_PAYMENT, MasterOrderStatus.PAID,
                MasterOrderStatus.PARTIALLY_SHIPPED, MasterOrderStatus.SHIPPED,
                MasterOrderStatus.COMPLETED, MasterOrderStatus.CANCELLED,
                MasterOrderStatus.REFUNDING, MasterOrderStatus.REFUNDED,
                MasterOrderStatus.CLOSED);
        assertThat(MasterOrderStatus.values()).hasSize(9);
    }

    @Test
    @DisplayName("状态解析：子单/主单状态名解析可用")
    void status_fromName_resolves() {
        assertThat(SubOrderStatus.fromName("PENDING_PAYMENT")).isEqualTo(SubOrderStatus.PENDING_PAYMENT);
        assertThat(MasterOrderStatus.fromName("PARTIALLY_SHIPPED")).isEqualTo(MasterOrderStatus.PARTIALLY_SHIPPED);
    }

    // ---------- 派生纯函数签名 ----------

    @Test
    @DisplayName("派生器：静态纯函数 derive(List<SubOrderStatus>) → MasterOrderStatus")
    void deriver_staticMethodFrozen() throws Exception {
        final Method derive = MasterOrderStatusDeriver.class.getMethod("derive", List.class);
        assertThat(Modifier.isStatic(derive.getModifiers())).isTrue();
        assertThat(derive.getReturnType()).isEqualTo(MasterOrderStatus.class);
        assertThat(derive.getParameterTypes()[0]).isEqualTo(List.class);
    }

    @Test
    @DisplayName("派生器：禁止实例化（纯函数载体）")
    void deriver_noPublicConstructor() throws Exception {
        final var ctor = MasterOrderStatusDeriver.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
    }

    // ---------- 聚合行为签名 ----------

    @Test
    @DisplayName("子单聚合：七迁移方法签名定型")
    void subOrder_migrationSignaturesFrozen() throws Exception {
        assertThat(MasterOrder.class.getMethod("deriveStatus", Collection.class)
                .getReturnType()).isEqualTo(MasterOrderStatus.class);
        assertThat(SubOrder.class.getMethod("markPaid").getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("markShipped", Long.class, Long.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("markCompleted").getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("cancel").getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("markRefunding").getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("markRefunded").getReturnType()).isEqualTo(void.class);
        assertThat(SubOrder.class.getMethod("closeByTimeout").getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("子单聚合读取面：归属/单号/快照/状态/运单")
    void subOrder_readSignaturesFrozen() throws Exception {
        assertThat(SubOrder.class.getMethod("getId").getReturnType()).isEqualTo(Long.class);
        assertThat(SubOrder.class.getMethod("getMasterOrderId").getReturnType()).isEqualTo(Long.class);
        assertThat(SubOrder.class.getMethod("getShopId").getReturnType()).isEqualTo(Long.class);
        assertThat(SubOrder.class.getMethod("getSubOrderNo").getReturnType()).isEqualTo(String.class);
        assertThat(SubOrder.class.getMethod("getWaybillId").getReturnType()).isEqualTo(Long.class);
        assertThat(SubOrder.class.getMethod("getStatus").getReturnType()).isEqualTo(SubOrderStatus.class);
        assertThat(SubOrder.class.getMethod("getItems").getReturnType().getSimpleName()).isEqualTo("List");
    }

    // ---------- 门面契约（ACL 冻结，0.5 契约表） ----------

    @Test
    @DisplayName("订单门面：onPaid/cancel/markShipped/autoComplete 签名定型")
    void orderFacade_signaturesFrozen() throws Exception {
        assertThat(OrderFacade.class.getMethod("onPaid", Long.class).getReturnType())
                .isEqualTo(void.class);
        assertThat(OrderFacade.class.getMethod("cancel", Long.class, String.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(OrderFacade.class.getMethod("markShipped", Long.class, Long.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(OrderFacade.class.getMethod("autoComplete", Long.class).getReturnType())
                .isEqualTo(void.class);
        assertThat(OrderFacade.class.getDeclaredMethods()).hasSize(4);
    }

    // ---------- 仓储契约 ----------

    @Test
    @DisplayName("仓储：主单/子单仓储继承 DifferRepository 基类契约")
    void repository_baseContract() {
        assertThat(BaseRepository.class.isAssignableFrom(MasterOrderRepository.class)).isTrue();
        assertThat(BaseRepository.class.isAssignableFrom(SubOrderRepository.class)).isTrue();
    }

    @Test
    @DisplayName("子单仓储：按主单装载契约（getByMasterOrderId）")
    void subOrderRepository_loadByMaster() throws Exception {
        final Method method = SubOrderRepository.class.getMethod("getByMasterOrderId", Long.class);
        assertThat(method.getReturnType().getSimpleName()).isEqualTo("List");
    }

    // ---------- 工厂契约 ----------

    @Test
    @DisplayName("工厂：主单/子单创建签名定型（ID 生成收敛工厂）")
    void factory_createSignaturesFrozen() throws Exception {
        assertThat(MasterOrderFactory.class.getMethod("createMasterOrder", String.class,
                Long.class, com.nona.domain.order.entity.AddressSnapshot.class,
                com.nona.domain.order.entity.AmountDetail.class, List.class, List.class)
                .getReturnType()).isEqualTo(MasterOrder.class);
        assertThat(SubOrderFactory.class.getMethod("createSubOrder", Long.class, Long.class,
                String.class, com.nona.domain.order.entity.AddressSnapshot.class,
                com.nona.domain.order.entity.AmountDetail.class, List.class)
                .getReturnType()).isEqualTo(SubOrder.class);
    }

    // ---------- 业务码（只增不改） ----------

    @Test
    @DisplayName("业务码：子单状态/归属/金额/空单码值钉死")
    void businessCodesFrozen() {
        assertThat(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code()).isEqualTo("order.master_not_found");
        assertThat(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code()).isEqualTo("order.sub_not_found");
        assertThat(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()).isEqualTo("order.sub_status_illegal");
        assertThat(EcommerceBusinessCode.ORDER_SUB_SHOP_MISMATCH.code()).isEqualTo("order.sub_shop_mismatch");
        assertThat(EcommerceBusinessCode.ORDER_SUB_AMOUNT_MISMATCH.code()).isEqualTo("order.sub_amount_mismatch");
        assertThat(EcommerceBusinessCode.ORDER_AMOUNT_MISMATCH.code()).isEqualTo("order.amount_mismatch");
        assertThat(EcommerceBusinessCode.ORDER_SUB_EMPTY.code()).isEqualTo("order.sub_empty");
        assertThat(EcommerceBusinessCode.defaultStatus("order.sub_status_illegal")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("order.sub_shop_mismatch")).isEqualTo(403);
    }
}