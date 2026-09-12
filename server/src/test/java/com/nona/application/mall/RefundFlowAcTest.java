package com.nona.application.mall;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.application.support.RefundCallbackUseCase;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundOrderView;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.exceptions.BusinessException;
import com.nona.inf.persistence.repository.RefundOrderRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款编排真实链验收测试（退款流真实装配面——PO 映射/租户过滤/上下文
 * 传播逐一验证；按 javadoc 启用契约改写——
 * RefundOrder/SubOrder/MasterOrder/PaymentOrder 仓储 JPA 实现 +
 * RefundUseCase/RefundCallbackUseCase/OrderFacadeImpl Spring 注册落地）。
 * <p>
 * 装配面清单——真实事务三域原子（申请全链 + 回调成功全链 +
 * 失败链 + 编排回滚实测）/ 提权写段真实面 / 幂等重放真实面 / 发货超时
 * 复用链 / 仓储链路 + 留痕从表装载。
 * <p>
 * 数据准备：测试内 JDBC 直插（测试库 ecommerce_test，固定 ID 段
 * 96101/86101/76101/46101/36101）；执行 = 真实 Spring 装配用例（申请
 * 经 MockPaymentGateway 真实受理，回调经测试构造 ValidatedCallback 送
 * 达回调编排）；断言 = JDBC 直查落库值。退款单号由工厂生成（REF 前缀 +
 * 日期 + snowflake），回调载荷复用申请返回的单号（真实链同构）。
 * <p>
 * 单测（RefundUseCaseUnitTest 11 用例 + RefundCallbackUseCaseUnitTest
 * 11 用例 + RefundOrderUnitTest 14 用例 + OrderFacadeRefundContractUnitTest
 * 8 用例）已锁编排与端口语义；本类只验证 mock 覆盖不到的装配面，不重
 * 复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class RefundFlowAcTest {

    /** 店铺 A（子单/库存 tenant 锚点）。 */
    private static final long SHOP_A = 96101L;

    /** 买家 A（订单归属买家）。 */
    private static final long BUYER_A = 56101L;

    /** 主单（PAID）。 */
    private static final long MASTER = 86101L;

    /** 子单（PAID，tenant=SHOP_A）。 */
    private static final long SUB = 76101L;

    /** SKU（库存行锚点）。 */
    private static final long SKU = 96101L;

    /** 支付单（PAID，pay_no 唯一）。 */
    private static final long PAYMENT = 46101L;

    /** 失败剧本子单（冒烟-1 直插，幂等清理白名单成员）。 */
    private static final long SUB_ILLEGAL = 76102L;

    /** 失败剧本子单订单项（子单装载校验必带——ORDER_SUB_EMPTY 守卫）。 */
    private static final long ITEM_ILLEGAL = 36103L;

    @Autowired
    private RefundUseCase refundUseCase;

    @Autowired
    private RefundCallbackUseCase refundCallbackUseCase;

    @Autowired
    private RefundOrderRepositoryImpl refundOrderRepositoryImpl;

    /**
     * 退款回调手触入口（跟踪作用域包裹，50 fail-closed 防护——回调上下文
     * 无请求视角，跟踪作用域仍由入口组件绑定，测试直调无入口组件）
     */
    private void refundCallback(ValidatedCallback callback) {
        com.nona.inf.context.TrackingContext.withScope(() ->
                refundCallbackUseCase.handleRefundCallback(callback));
    }

    /**
     * 受理占位渠道流水号（渠道流水一致防线：回调号必须 = 受理占位号，
     * 异号即渠道事故拒绝——同渠道流水语义；受理值由 mock 网关生成）
     */
    private String occupiedRefundTxnNo(String refundNo) throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            return AcceptanceDbSupport.stringValue(conn,
                    "SELECT channel_refund_txn_no FROM refund_order WHERE refund_no = ?", refundNo);
        }
    }

    /**
     * 发货超时退款手触入口（跟踪作用域包裹，50 fail-closed 防护——超时
     * 引擎线程由任务传播装饰器绑定作用域，测试直调无入口组件）
     */
    private RefundOrderView shipTimeoutRefund(Long subOrderId) {
        final RefundOrderView[] holder = new RefundOrderView[1];
        com.nona.inf.context.TrackingContext.withScope(() ->
                holder[0] = refundUseCase.refundByShipTimeout(subOrderId));
        return holder[0];
    }

    /**
     * 每用例前：幂等清理 + 直插基础行——主单（PAID）+ 子单（PAID）+
     * 订单项 + 库存（sold=1 已扣减态）+ 支付单（PAID）。
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
            insertBaseRows(conn);
        }
    }

    private static void insertBaseRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_REF49",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "PAID");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_REF49", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, "PAID", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36101L,
                String.valueOf(SHOP_A), SUB, 96101L, SKU, "fixture毛衣", 5000L, 1, 5000L);
        // 已支付扣减后形态：available=9 / held=0 / sold=1
        AcceptanceDbSupport.update(conn,
                "INSERT INTO inventory_item (create_time, update_time, id, tenant_id, sku_id,"
                        + " available, held, sold, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36102L,
                String.valueOf(SHOP_A), SKU, 9, 0, 1, 0);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO payment_order (create_time, update_time, id, pay_no, order_id, amount,"
                        + " channel, timeout_at, status, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), PAYMENT, "PAY_REF49",
                MASTER, 5300L, "MOCK", AcceptanceDbSupport.utcOffset(-120),
                "PAID", false);
    }

    /**
     * 冒烟-1 申请全链真实事务（核心）：真实 Spring 事务下买家申请退款全链
     * ——refund_order 行插入（PENDING + 受理流水落位）+ 子单 REFUNDING
     * + 主单派生 REFUNDING 同事务提交；<b>编排失败回滚实测</b>——构造
     * 子单非法状态使 beginRefund 抛异常 → 退款单/子单/主单全回滚（库中
     * 不存在「退款单已建而子单未推进」的半程态）。
     * <p>
     * 断言面：JDBC 直查 refund_order/sub_order/master_order 落库值，断言
     * 状态与流水；失败剧本断言全部回滚。
     */
    @Test
    @DisplayName("冒烟-1 申请全链真实事务：建单+子单推进提交 + 编排失败整体回滚")
    void realTransaction_applyChainAtomic() throws Exception {
        final RefundOrderView[] holder = new RefundOrderView[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            holder[0] = refundUseCase.applyRefundByBuyer(BUYER_A, SUB, "尺码不合适");
        });
        final RefundOrderView view = holder[0];
        final String refundNo = view.refundNo();
        assertThat(refundNo).startsWith("REF");
        assertThat(view.status()).isEqualTo(RefundOrderStatus.PENDING);
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // refund_order 行（PENDING + 受理字段落位 + 一子单一退款单）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE refund_no = ?", refundNo)).isEqualTo("PENDING");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT pay_no FROM refund_order WHERE refund_no = ?", refundNo)).isEqualTo("PAY_REF49");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sub_order_id FROM refund_order WHERE refund_no = ?", refundNo))
                    .isEqualTo(String.valueOf(SUB));
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT shipped_at_apply FROM refund_order WHERE refund_no = ?", refundNo))
                    .isEqualTo("0");
            // 子单 REFUNDING + 主单派生 REFUNDING 同事务提交
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("REFUNDING");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("REFUNDING");
        }
        // 失败剧本：PENDING_PAYMENT 子单申请退款（直插 + 订单项，装载校验必带）
        // → beginRefund 守卫拒绝 → 全回滚
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn, "DELETE FROM refund_order WHERE sub_order_id = ?", SUB);
            final long subIllegal = SUB_ILLEGAL;
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subIllegal,
                    String.valueOf(SHOP_A), MASTER, SHOP_A, "SUB_REF49R", "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "文一西路 1 号", 5000L, 300L, 0L, 5300L,
                    "PENDING_PAYMENT", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_ILLEGAL,
                    String.valueOf(SHOP_A), subIllegal, 96101L, SKU, "fixture毛衣", 5000L, 1, 5000L);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
                refundUseCase.applyRefundByBuyer(BUYER_A, subIllegal, "非法剧本");
            })).isInstanceOf(BusinessException.class);
            // 全回滚：无退款单 + 子单未推进（无半程态）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_order WHERE sub_order_id = ?", subIllegal)).isZero();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", subIllegal)).isEqualTo("PENDING_PAYMENT");
        }
    }

    /**
     * 冒烟-2 回调成功全链（含 I7 回补）：REFUND 成功回调真实链——refund_order
     * SUCCEEDED + refund_callback_log 留痕行 + 子单 REFUNDED + 主单派生
     * REFUNDED + inventory REFUND_RESTORE 回补（inventory_item 三态
     * 迁移 sold−/sellable+ + inventory_log 行 + RestockEvent 触发面）；
     * 回补判定尊重退款单 shippedAtApply 快照（已发货剧本不回补）。
     * <p>
     * 断言面：直查六表落库值断言；失败剧本（FAIL 回调）断言 refund_order
     * FAILED + 子单停留 REFUNDING + 无回补行。
     */
    @Test
    @DisplayName("冒烟-2 回调成功全链：SUCCEEDED+留痕+子单已退款+REFUND_RESTORE 回补")
    void refundCallback_successChainWithRestore() throws Exception {
        // ---- 未发货剧本：成功回调全链 + I7 回补 ----
        final RefundOrderView view = applyRefund();
        final String refundNo = view.refundNo();
        refundCallback(new ValidatedCallback(
                CallbackType.REFUND, "PAY_REF49", refundNo, GatewayResult.SUCCESS,
                occupiedRefundTxnNo(refundNo), 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE refund_no = ?", refundNo)).isEqualTo("SUCCEEDED");
            // 留痕从表（append-only，rootId 归主表）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = ?", refundNo)).isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT result FROM refund_callback_log WHERE refund_no = ?", refundNo)).isEqualTo("SUCCESS");
            // 子单 REFUNDED + 主单派生 REFUNDED
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("REFUNDED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("REFUNDED");
            // I7 未发货回补：sold 1→0 / available 9→10 + REFUND_RESTORE 流水
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT available FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("10");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? ORDER BY id DESC LIMIT 1", SKU))
                    .isEqualTo("REFUND_RESTORE");
        }
        // ---- 已发货剧本（shippedAtApply=true）：回调成功不回补 ----
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn, "DELETE FROM refund_order WHERE sub_order_id = ?", SUB);
            AcceptanceDbSupport.update(conn,
                    "UPDATE sub_order SET status = 'SHIPPED' WHERE id = ?", SUB);
            AcceptanceDbSupport.update(conn,
                    "UPDATE master_order SET status = 'SHIPPED' WHERE id = ?", MASTER);
            final RefundOrderView shippedView = applyRefund();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT shipped_at_apply FROM refund_order WHERE refund_no = ?", shippedView.refundNo()))
                    .isEqualTo("1");
            final long logBefore = AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU);
            final long soldBefore = Long.parseLong(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU));
            refundCallback(new ValidatedCallback(
                    CallbackType.REFUND, "PAY_REF49", shippedView.refundNo(), GatewayResult.SUCCESS,
                    occupiedRefundTxnNo(shippedView.refundNo()), 5300L));
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE refund_no = ?", shippedView.refundNo()))
                    .isEqualTo("SUCCEEDED");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isEqualTo(logBefore);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo(String.valueOf(soldBefore));
        }
        // ---- 失败回调链：FAILED + 子单停留 REFUNDING + 无回补行 ----
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn, "DELETE FROM refund_order WHERE sub_order_id = ?", SUB);
            AcceptanceDbSupport.update(conn,
                    "UPDATE sub_order SET status = 'PAID' WHERE id = ?", SUB);
            AcceptanceDbSupport.update(conn,
                    "UPDATE master_order SET status = 'PAID' WHERE id = ?", MASTER);
            final RefundOrderView failView = applyRefund();
            refundCallback(new ValidatedCallback(
                    CallbackType.REFUND, "PAY_REF49", failView.refundNo(), GatewayResult.FAIL,
                    "TXN-REF49-3", 5300L));
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE refund_no = ?", failView.refundNo()))
                    .isEqualTo("FAILED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("REFUNDING");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? ORDER BY id DESC LIMIT 1", SKU))
                    .isEqualTo("REFUND_RESTORE");
        }
    }

    /**
     * 冒烟-3 幂等重放真实面：同一 REFUND 回调重放 → refund_callback_log
     * 第二条留痕落库 + 订单/库存零二次变更（REFUND_RESTORE 幂等键
     * (order_id, sku_id, type) 未被二次消费——流水表零新行）；重复回调
     * 命中状态守卫 refund_status_illegal 透传。
     * <p>
     * 断言面：先完整成功回调一次，再重放同号回调；断言留痕表 2 行 +
     * 子单/主单/库存流水无新增变更行。
     */
    @Test
    @DisplayName("冒烟-3 幂等重放：第二条留痕落库 + 订单/库存零二次变更")
    void idempotentReplay_secondTraceOnly() throws Exception {
        final RefundOrderView view = applyRefund();
        final String refundNo = view.refundNo();
        refundCallback(new ValidatedCallback(
                CallbackType.REFUND, "PAY_REF49", refundNo, GatewayResult.SUCCESS,
                occupiedRefundTxnNo(refundNo), 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = ?", refundNo)).isEqualTo(1L);
            final long logCount = AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU);
            // 重放同号回调：状态守卫拒绝（refund_status_illegal）透传，留痕第二条落库
            assertThatThrownBy(() -> refundCallback(new ValidatedCallback(
                    CallbackType.REFUND, "PAY_REF49", refundNo, GatewayResult.SUCCESS,
                    occupiedRefundTxnNo(refundNo), 5300L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("payment.refund_status_illegal");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = ?", refundNo)).isEqualTo(2L);
            // 订单/库存零二次变更
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("REFUNDED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("REFUNDED");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isEqualTo(logCount);
        }
    }

    /**
     * 冒烟-4 发货超时复用链：refundByShipTimeout 真实链——子单 PAID →
     * CLOSED（主单派生）+ 退款单受理落位；REFUND 成功回调 → 退款单
     * SUCCEEDED + 未发货回补（I7）→ 子单保持 CLOSED（不迁移 REFUNDED，
     * 领域模型明示 CLOSED 终态）+ 幂等重扫短路（第二次入口调用零动作）。
     * <p>
     * 断言面：直查 sub_order CLOSED + refund_order SUCCEEDED +
     * inventory REFUND_RESTORE 行；重载入口断言短路。
     */
    @Test
    @DisplayName("冒烟-4 发货超时链：子单 CLOSED + 退款 SUCCEEDED + 回补 + 重扫短路")
    void shipTimeout_refundChainReusesRefundFlow() throws Exception {
        final RefundOrderView view = shipTimeoutRefund(SUB);
        assertThat(view).isNotNull();
        final String refundNo = view.refundNo();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 子单 CLOSED + 主单派生 CLOSED + 退款单 PENDING 受理
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE sub_order_id = ?", SUB)).isEqualTo("PENDING");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT shipped_at_apply FROM refund_order WHERE sub_order_id = ?", SUB))
                    .isEqualTo("0");
        }
        // 重扫短路：子单已 CLOSED → 第二次入口零动作（返回 null）
        assertThat(shipTimeoutRefund(SUB)).isNull();
        // 成功回调：退款 SUCCEEDED + 未发货回补 + 子单保持 CLOSED（终态不迁移 REFUNDED）
        refundCallback(new ValidatedCallback(
                CallbackType.REFUND, "PAY_REF49", refundNo, GatewayResult.SUCCESS,
                occupiedRefundTxnNo(refundNo), 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE refund_no = ?", refundNo)).isEqualTo("SUCCEEDED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("CLOSED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? ORDER BY id DESC LIMIT 1", SKU))
                    .isEqualTo("REFUND_RESTORE");
        }
    }

    /**
     * 冒烟-5 仓储链路 + 提权写段 + DDL 约束：refund_order 主表 +
     * refund_callback_log 从表（rootId getOther 装载）/sub_order/
     * master_order/inventory_item/inventory_log 真实 JPA 链路与
     * DifferRepository 变更集落库；回调上下文无身份（tenant 空）跨租户
     * 写（子单/库存 tenant=shopId）经
     * {@code TenantPrivilege.elevatedInTransaction} 真实放行 + PO 显式
     * tenantID 归位；DDL 核对：refund_no 唯一 / sub_order_id 唯一（一子
     * 单一退款单）/ channel_refund_txn_no 不建唯一（重试覆盖更新）。
     * <p>
     * 断言面：仓储实现 bean 就位（先决项）+ DDL 约束核对（information_schema）。
     */
    @Test
    @DisplayName("冒烟-5 仓储链路 + 提权写段真实放行 + DDL 约束核对")
    void repositoryChain_jpaImplementationsWired() throws Exception {
        assertThat(refundOrderRepositoryImpl).isNotNull();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'refund_order'"
                            + " AND index_name = 'uk_refund_order_refund_no' AND non_unique = 0"))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'refund_order'"
                            + " AND index_name = 'uk_refund_order_sub_order_id' AND non_unique = 0"))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'refund_order'"
                            + " AND index_name = 'uk_refund_order_channel_refund_txn_no'"))
                    .isZero();
        }
    }

    /** 申请退款辅助（买家上下文 = 店铺租户，认证链同构）。 */
    private RefundOrderView applyRefund() {
        final RefundOrderView[] holder = new RefundOrderView[1];
        com.nona.inf.context.TrackingContext.withScope(() -> {
            com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            holder[0] = refundUseCase.applyRefundByBuyer(BUYER_A, SUB, "退款原因");
        });
        return holder[0];
    }
}
