package com.nona.domain.payment;

import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.factory.PaymentOrderFactory;
import com.nona.domain.payment.ports.PaymentCallbackPort;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付单域契约钉测试（红阶段：纯只读断言——枚举值/业务码码值/
 * 签名面，不触碰实现逻辑即绿；UOE 断言锁定红态防半实现回潮）。
 * <p>
 * <b>绿阶段改造记录（上报主会话）</b>：契约-8/9 原为红阶段「UOE 自证
 * 防线」（断言实现缺失的红态），与绿阶段实现互斥——红报告第七节
 * 「11 个契约钉测试保持绿」的预设要求绿阶段将其改造为<b>绿态防线</b>：
 * 断言实现已落地（任何方法回退成 UOE 或行为偏离即红），用例数与
 * 防线意图不变（防半实现回潮的反向形态）。
 * <p>
 * 其余用例为「设计物契约」的成立（非实现）：枚举常量、业务码、签名
 * 声明是红阶段契约本体，实现不触碰。
 */
class PaymentContractTest {

    // ------------------------------------------------------------------
    // 状态值定型（设计契约：design §5.5 状态机 4 值，本阶段冻结）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约-1 支付单状态值定型：待支付/已支付/已失败/已关闭 4 值顺序不可改")
    void paymentOrderStatusValuesFixed() {
        assertThat(PaymentOrderStatus.values())
                .containsExactly(PaymentOrderStatus.PENDING_PAYMENT, PaymentOrderStatus.PAID,
                        PaymentOrderStatus.FAILED, PaymentOrderStatus.CLOSED);
        assertThat(PaymentOrderStatus.PENDING_PAYMENT.name()).isEqualTo("PENDING_PAYMENT");
        assertThat(PaymentOrderStatus.PAID.name()).isEqualTo("PAID");
        assertThat(PaymentOrderStatus.FAILED.name()).isEqualTo("FAILED");
        assertThat(PaymentOrderStatus.CLOSED.name()).isEqualTo("CLOSED");
    }

    // ------------------------------------------------------------------
    // 业务码码值（只增不改：payment 段本位 4 码）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约-2 业务码码值与状态映射冻结（只增不改）")
    void paymentBusinessCodesFixed() {
        assertThat(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code()).isEqualTo("payment.order_invalid");
        assertThat(EcommerceBusinessCode.defaultStatus("payment.order_invalid")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code()).isEqualTo("payment.status_illegal");
        assertThat(EcommerceBusinessCode.defaultStatus("payment.status_illegal")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code()).isEqualTo("payment.amount_mismatch");
        assertThat(EcommerceBusinessCode.defaultStatus("payment.amount_mismatch")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code()).isEqualTo("payment.callback_duplicate");
        assertThat(EcommerceBusinessCode.defaultStatus("payment.callback_duplicate")).isEqualTo(409);
        // 既有码未被改动（回归防线）
        assertThat(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code()).isEqualTo("payment.not_found");
        assertThat(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code())
                .isEqualTo("payment.gateway_invalid_argument");
    }

    // ------------------------------------------------------------------
    // 签名面（端口/仓储/聚合/工厂——本阶段冻结的消费面）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约-3 PaymentPort 签名面：createPendingPayment（冻结契约）+ closePay（关单契约）")
    void paymentPortSignatureSurface() throws NoSuchMethodException {
        assertThat(PaymentPort.class.getMethod("createPendingPayment", Long.class, long.class, long.class))
                .isNotNull();
        assertThat(PaymentPort.class.getMethod("closePay", String.class)).isNotNull();
        assertThat(PaymentPort.class.getDeclaredMethod("createPendingPayment", Long.class, long.class, long.class))
                .isNotNull();
        assertThat(PaymentPort.class.getDeclaredMethod("closePay", String.class)).isNotNull();
    }

    @Test
    @DisplayName("契约-4 PaymentCallbackPort 签名面：handlePayCallback（回调处理入口）")
    void paymentCallbackPortSignatureSurface() throws NoSuchMethodException {
        assertThat(PaymentCallbackPort.class.getDeclaredMethod("handlePayCallback",
                com.nona.domain.payment.ports.ValidatedCallback.class)).isNotNull();
    }

    @Test
    @DisplayName("契约-5 PaymentOrderRepository 签名面：findByPayNo / findByOrderId（回调/复用装载面）")
    void paymentOrderRepositorySignatureSurface() throws NoSuchMethodException {
        assertThat(PaymentOrderRepository.class.getDeclaredMethod("findByPayNo", String.class)).isNotNull();
        assertThat(PaymentOrderRepository.class.getDeclaredMethod("findByOrderId", Long.class)).isNotNull();
        assertThat(PaymentOrderRepository.class.getInterfaces()).contains(
                com.nona.persistence.BaseRepository.class);
    }

    @Test
    @DisplayName("契约-6 PaymentOrder 聚合签名面：迁移 3 + 留痕 1 + 读取 9 方法齐备")
    void paymentOrderAggregateSignatureSurface() throws NoSuchMethodException {
        assertThat(PaymentOrder.class.getMethod("markPaid", String.class, long.class)).isNotNull();
        assertThat(PaymentOrder.class.getMethod("markFailed", String.class, long.class)).isNotNull();
        assertThat(PaymentOrder.class.getMethod("close")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("appendCallbackRecord", PaymentCallbackRecord.class)).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getId")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getPayNo")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getOrderId")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getAmount")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getChannel")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getTimeoutAt")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getStatus")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getChannelTxnNo")).isNotNull();
        assertThat(PaymentOrder.class.getMethod("getCallbacks")).isNotNull();
    }

    @Test
    @DisplayName("契约-7 工厂签名面：create 是唯一创建入口（生成逻辑收敛工厂一处）")
    void factorySignatureSurface() throws NoSuchMethodException {
        assertThat(PaymentOrderFactory.class.getMethod("create", Long.class, long.class,
                String.class, long.class)).isNotNull();
    }

    // ------------------------------------------------------------------
    // UOE 红阶段防线（防半实现回潮：任何实现一旦落地即转红）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约-8 绿态防线：聚合全部读取/迁移方法已实现落地（防回退 UOE/行为偏离）")
    void aggregateMethodsAreImplementedInGreenPhase() {
        final PaymentOrder order = new PaymentOrder(
                1L, "PAY202609070001", 100L, 10000L, "MOCK",
                Instant.parse("2026-09-07T10:00:00Z"));
        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getPayNo()).isEqualTo("PAY202609070001");
        assertThat(order.getOrderId()).isEqualTo(100L);
        assertThat(order.getAmount()).isEqualTo(10000L);
        assertThat(order.getChannel()).isEqualTo("MOCK");
        assertThat(order.getTimeoutAt()).isEqualTo(Instant.parse("2026-09-07T10:00:00Z"));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PAYMENT);
        assertThat(order.getChannelTxnNo()).isNull();
        assertThat(order.getCallbacks()).isEmpty();
        order.appendCallbackRecord(new PaymentCallbackRecord(
                11L, 1L, com.nona.domain.payment.ports.CallbackType.PAY, "PAY202609070001",
                null, com.nona.domain.payment.ports.GatewayResult.SUCCESS,
                "TXN-ALIPAY-001", 10000L, Instant.parse("2026-09-07T10:01:00Z")));
        assertThat(order.getCallbacks()).hasSize(1);
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
        final PaymentOrder closed = new PaymentOrder(
                2L, "PAY202609070002", 100L, 10000L, "MOCK",
                Instant.parse("2026-09-07T10:00:00Z"));
        closed.markFailed("TXN-ALIPAY-002", 10000L);
        closed.close();
        assertThat(closed.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
        assertThatThrownBy(() -> order.appendCallbackRecord(null))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> new PaymentOrderFactory()
                .create(100L, 10000L, "MOCK", 1_800_000L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("契约-9 绿态防线：留痕实体读取方法全部实现落地（防回退 UOE/行为偏离）")
    void callbackRecordMethodsAreImplementedInGreenPhase() {
        final PaymentCallbackRecord record = new PaymentCallbackRecord(
                11L, 1L, com.nona.domain.payment.ports.CallbackType.PAY, "PAY202609070001",
                null, com.nona.domain.payment.ports.GatewayResult.SUCCESS,
                "TXN-ALIPAY-001", 10000L, Instant.parse("2026-09-07T10:01:00Z"));
        assertThat(record.getId()).isEqualTo(11L);
        assertThat(record.getPaymentOrderId()).isEqualTo(1L);
        assertThat(record.getCallbackType()).isEqualTo(com.nona.domain.payment.ports.CallbackType.PAY);
        assertThat(record.getPayNo()).isEqualTo("PAY202609070001");
        assertThat(record.getRefundNo()).isNull();
        assertThat(record.getResult()).isEqualTo(com.nona.domain.payment.ports.GatewayResult.SUCCESS);
        assertThat(record.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
        assertThat(record.getAmountCents()).isEqualTo(10000L);
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-09-07T10:01:00Z"));
    }

    @Test
    @DisplayName("契约-10 持久化形态契约：留痕从表以 rootId 归属聚合——记录承载 paymentOrderId（无独立聚合宣称）")
    void callbackRecordRootedAtAggregate() {
        // 归属字段（paymentOrderId = payment_order 主键）为从表关联的唯一锚点：
        // PaymentCallbackRecord 不持有独立仓储/根身份宣称（基类断言：非 BaseRepository 根）
        assertThat(PaymentCallbackRecord.class.getMethods())
                .filteredOn(m -> m.getName().equals("getPaymentOrderId"))
                .hasSize(1);
        assertThat(List.of(PaymentCallbackRecord.class.getInterfaces())).isEmpty();
    }
}