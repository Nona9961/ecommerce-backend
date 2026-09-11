package com.nona.application.mall;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.exceptions.BusinessException;
import com.nona.inf.order.ReceiveTimeoutStore;
import com.nona.inf.order.ShipTimeoutStore;
import com.nona.inf.payment.PayTimeoutStore;
import com.nona.inf.timeout.TimeoutTask;
import com.nona.inf.timeout.TimeoutTaskProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三超时业务处理器真实链验收测试（支付超时/发货超时/收货超时：
 * 真实链装配面——
 * store 认领/清除 SQL、租户列、仓储链路、调度激活逐一验证；按
 * javadoc 启用契约改写——仓储 JPA 实现 +
 * 用例/处理器/store Spring 注册落地，调度门控 test=false 手触）。
 * <p>
 * 装配面清单：支付超时关单链路 / 发货超时
 * 退款链路 / 收货超时自动完成链路。
 * <p>
 * 执行形态：test=false 调度关闭（无自动滴答干扰），经
 * {@link TimeoutTaskProcessor#processOne} 手触（SchedulingAssemblyAcTest
 * 同款手触入口）——store 注入真实 bean，候选构造 = 超时 SQL 面三列
 * （timeout_at/timeout_type/claimed）直插；断言 = JDBC 直查落库值。
 * <p>
 * 单测（{@code PayTimeoutHandler/ShipTimeoutHandler/ReceiveTimeoutHandler
 * UnitTest} 与三个 StoreUnitTest）已锁路由/端口语义；本类只验证 mock
 * 覆盖不到的装配面，不重复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class TimeoutHandlersAcTest {

    /** 店铺 A（子单/库存 tenant 锚点）。 */
    private static final long SHOP_A = 96101L;

    /** 买家 A（订单归属买家）。 */
    private static final long BUYER_A = 56101L;

    /** 主单（支付超时/发货超时剧本共用锚点）。 */
    private static final long MASTER = 86101L;

    /** 子单（tenant=SHOP_A）。 */
    private static final long SUB = 76101L;

    /** SKU（库存行锚点）。 */
    private static final long SKU = 96101L;

    /** 支付单（支付超时剧本锚点）。 */
    private static final long PAYMENT = 46101L;

    @Autowired
    private TimeoutTaskProcessor processor;

    @Autowired
    private PayTimeoutStore payTimeoutStore;

    @Autowired
    private ShipTimeoutStore shipTimeoutStore;

    @Autowired
    private ReceiveTimeoutStore receiveTimeoutStore;

    /**
     * 每用例前：幂等清理本类造数。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 主单维度幂等清理（跨类同段残留兼容：getByMasterOrderId 反查面
            // 不混入他类直插的挂同主单子单）
            AcceptanceDbSupport.update(conn, "DELETE FROM refund_callback_log WHERE refund_order_id IN"
                    + " (SELECT id FROM refund_order WHERE sub_order_id IN (SELECT id FROM sub_order"
                    + " WHERE master_order_id = ?))", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM refund_order WHERE sub_order_id IN (SELECT id FROM sub_order"
                            + " WHERE master_order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn, "DELETE FROM inventory_log WHERE sku_id = ?", SKU);
            AcceptanceDbSupport.update(conn, "DELETE FROM inventory_item WHERE sku_id = ?", SKU);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM payment_callback_log WHERE payment_order_id IN"
                            + " (SELECT id FROM payment_order WHERE order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM payment_order WHERE order_id = ?", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM order_item WHERE sub_order_id IN (SELECT id FROM sub_order"
                            + " WHERE master_order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM sub_order WHERE master_order_id = ?", MASTER);
            AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id = ?", MASTER);
        }
    }

    /** 基础行直插（主单+子单+订单项+库存+支付单；status 可定制）。 */
    private static void insertRows(Connection conn, String masterStatus, String subStatus,
                                   String paymentStatus, int available, int held, int sold,
                                   String timeoutAt) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_TO49",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, masterStatus);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id,"
                        + " timeout_at, timeout_type, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_TO49", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, subStatus, null, null, null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36101L,
                String.valueOf(SHOP_A), SUB, 96101L, SKU, "fixture毛衣", 5000L, 1, 5000L);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO inventory_item (create_time, update_time, id, tenant_id, sku_id,"
                        + " available, held, sold, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36102L,
                String.valueOf(SHOP_A), SKU, available, held, sold, 0);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO payment_order (create_time, update_time, id, pay_no, order_id, amount,"
                        + " channel, timeout_at, status, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), PAYMENT, "PAY_TO49",
                MASTER, 5300L, "MOCK",
                timeoutAt == null ? AcceptanceDbSupport.utcOffset(-120)
                        : java.sql.Timestamp.valueOf(timeoutAt),
                paymentStatus, false);
    }

    /**
     * 冒烟-1 支付超时真实链路（核心）：真实 Spring 事务下到期候选经
     * 引擎 {@code TimeoutTaskProcessor.processOne} 认领 →
     * {@link PayTimeoutHandler#fire} → {@link CancelOrderUseCase#cancelByTimeout}
     * → 清除 deadline 闭环；断言 payment_order 行 claimed/timeout_at/
     * timeout_type 三要素落位与清除、主单/子单关单落库（status / 库存
     * 回滚幂等键面）。
     * <p>
     * 断言面：JDBC 直查 payment_order/sub_order/master_order/inventory
     * 落库值，断言预期态过滤（仅 PENDING_PAYMENT 参与认领——PAID 单
     * claim 返回 false 无副作用）与认领/清除 SQL 条件语义；回滚剧本
     * （孤儿支付单：主单不存在）→ 断言整体回滚（认领位复位，无死认领行）。
     */
    @Test
    @DisplayName("冒烟-1 支付超时：待支付到期 → 认领/关单/清除 deadline 真实事务闭环")
    void payTimeout_realChainClosedLoop() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            insertRows(conn, "PENDING_PAYMENT", "PENDING_PAYMENT", "PENDING_PAYMENT",
                    9, 1, 0, "2020-01-01 00:00:00");
        }
        // 手触认领执行（test=false 调度关闭；候选构造：id=支付单，target=主单）
        // 手触入口与认证链同构：未绑定跟踪作用域触发 50 fail-closed（ISE），
        // 必须以 TrackingContext.withScope 包裹（超时引擎线程由任务传播装饰器
        // 绑定作用域，测试直调无入口组件）
        final boolean[] handled = new boolean[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            handled[0] = processor.processOne(payTimeoutStore,
                    new TimeoutTask<>(PAYMENT, MASTER));
        });
        assertThat(handled[0]).isTrue();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 支付单：CLOSED + 三要素清除（timeout_at NULL / timeout_type NULL / claimed 0）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT timeout_at FROM payment_order WHERE id = ?", PAYMENT)).isNull();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT timeout_type FROM payment_order WHERE id = ?", PAYMENT)).isNull();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT claimed FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("0");
            // 主单/子单关单落库 + 库存回滚（held 1→0 / available 9→10）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("CANCELLED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("CANCELLED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT held FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT available FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("10");
        }
        // 预期态过滤：PAID 支付单不参与认领（claim 条件 status=PENDING_PAYMENT → false，无副作用）
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn,
                    "UPDATE payment_order SET status = 'PAID', timeout_at = '2020-01-01 00:00:00',"
                            + " claimed = 0 WHERE id = ?", PAYMENT);
            final boolean[] claimed = new boolean[1];
            com.nona.inf.context.TrackingContext.withScope(() -> {
                claimed[0] = processor.processOne(payTimeoutStore,
                        new TimeoutTask<>(PAYMENT, MASTER));
            });
            assertThat(claimed[0]).isFalse();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT claimed FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("PAID");
        }
        // 回滚剧本：孤儿支付单（主单不存在）→ 编排抛错 → 认领位复位（无死认领行）
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id = ?", MASTER);
            AcceptanceDbSupport.update(conn,
                    "UPDATE payment_order SET status = 'PENDING_PAYMENT', timeout_at = '2020-01-01 00:00:00',"
                            + " claimed = 0 WHERE id = ?", PAYMENT);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                processor.processOne(payTimeoutStore,
                        new TimeoutTask<>(PAYMENT, MASTER));
            })).isInstanceOf(BusinessException.class);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT claimed FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("PENDING_PAYMENT");
        }
    }

    /**
     * 冒烟-2 发货超时真实链路：到期已支付子单 → 认领 →
     * {@link ShipTimeoutHandler#fire} → {@link RefundUseCase#refundByShipTimeout}
     * （closeByTimeout + 退款单建单受理；<b>I7 库存回补在退款回调成功链</b>
     * ——受理 ≠ 退款成功，与 {@code RefundFlowAcTest} shipTimeout 剧本分工）
     * → 清除 deadline；断言 sub_order/refund_order 落库、库存保持未回补
     * 与短路语义（非 PAID 子单重扫短路不重复建单）。
     * <p>
     * 断言面：直查 sub_order/refund_order/inventory 断言落库值与租户列；
     * 重复触发断言短路（一子单一退款单不重复建单）。
     */
    @Test
    @DisplayName("冒烟-2 发货超时：已支付未发货到期 → 认领/关单退款（回补在退款回调链）真实事务闭环")
    void shipTimeout_realChainClosedLoop() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 已支付扣减后形态（available=9/sold=1）+ 子单 PAID 到期
            insertRows(conn, "PAID", "PAID", "PAID", 9, 0, 1, "2020-01-01 00:00:00");
            AcceptanceDbSupport.update(conn,
                    "UPDATE sub_order SET timeout_at = '2020-01-01 00:00:00',"
                            + " timeout_type = 'ORDER_SHIP', claimed = 0 WHERE id = ?", SUB);
        }
        final boolean[] handled = new boolean[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            handled[0] = processor.processOne(shipTimeoutStore,
                    new TimeoutTask<>(SUB, SUB));
        });
        assertThat(handled[0]).isTrue();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 子单 CLOSED + 主单派生 CLOSED + deadline 清除
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT timeout_at FROM sub_order WHERE id = ?", SUB)).isNull();
            // 退款单受理落位（一子单一退款单）；库存未回补（受理 ≠ 退款成功，
            // 回补在退款回调成功链——RefundFlowAcTest shipTimeout 剧本覆盖）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_order WHERE sub_order_id = ?", SUB)).isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE sub_order_id = ?", SUB)).isEqualTo("PENDING");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("1");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT available FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("9");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isZero();
        }
        // 重复触发短路：deadline 已清除 + 子单已 CLOSED → claim 失败返回 false，不重复建单
        final boolean[] replayed = new boolean[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            replayed[0] = processor.processOne(shipTimeoutStore, new TimeoutTask<>(SUB, SUB));
        });
        assertThat(replayed[0]).isFalse();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_order WHERE sub_order_id = ?", SUB)).isEqualTo(1L);
        }
    }

    /**
     * 冒烟-3 收货超时真实链路：到期已发货子单 → 认领 →
     * {@link ReceiveTimeoutHandler#fire} →
     * {@link ConfirmReceiptUseCase#autoCompleteByTimeout}（自动完成 +
     * 完成事件发布）→ 清除 deadline；断言 sub_order/main 单完成迁移、
     * 幂等短路（已完成子单重扫短路不重复发布事件）。
     * <p>
     * 断言面：直查 sub_order/master_order 断言完成迁移；重复触发断言
     * 短路（完成事件至多一次）。
     */
    @Test
    @DisplayName("冒烟-3 收货超时：已发货到期 → 认领/自动完成（事件至多一次）真实事务闭环")
    void receiveTimeout_realChainClosedLoop() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            insertRows(conn, "SHIPPED", "SHIPPED", "PAID", 9, 0, 1, null);
            AcceptanceDbSupport.update(conn,
                    "UPDATE sub_order SET timeout_at = '2020-01-01 00:00:00',"
                            + " timeout_type = 'ORDER_RECEIVE', claimed = 0 WHERE id = ?", SUB);
        }
        final boolean[] handled = new boolean[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            handled[0] = processor.processOne(receiveTimeoutStore,
                    new TimeoutTask<>(SUB, SUB));
        });
        assertThat(handled[0]).isTrue();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 完成迁移 + deadline 清除
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT timeout_at FROM sub_order WHERE id = ?", SUB)).isNull();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT claimed FROM sub_order WHERE id = ?", SUB)).isEqualTo("0");
        }
        // 重复触发短路：已完成子单重扫 → claim 失败返回 false（不重复发布完成事件）
        final boolean[] replayed = new boolean[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            replayed[0] = processor.processOne(receiveTimeoutStore,
                    new TimeoutTask<>(SUB, SUB));
        });
        assertThat(replayed[0]).isFalse();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("COMPLETED");
        }
    }
}
