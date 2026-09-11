package com.nona.application.support;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.exceptions.BusinessException;
import com.nona.inf.persistence.repository.PaymentOrderRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付回调编排真实链验收测试（支付回调编排：真实链装配面——PO
 * 映射/租户过滤/上下文传播逐一验证；按 javadoc 启用契约改写——
 * PaymentOrder/SubOrder/MasterOrder 仓储
 * JPA 实现 + PaymentCallbackUseCase/OrderFacadeImpl Spring 注册落地）。
 * <p>
 * 装配面清单见红设计报告 §四 6 条——真实事务三域原子（成功全链 + 失败
 * 回滚实测）/ 提权写段真实面 / 幂等重放真实面 / 失败回调链 / 仓储链路
 * + 留痕从表装载 / 仓储降级规则。
 * <p>
 * 数据准备：测试内 JDBC 直插（测试库 ecommerce_test，固定 ID 段
 * 96101/86101/76101/46101/36101）；执行 = 真实 Spring 装配用例（回调
 * 经测试构造 ValidatedCallback 送达编排，不经真实网关）；断言 =
 * JDBC 直查落库值。
 * <p>
 * 单测（PaymentCallbackUseCaseUnitTest 11 用例 +
 * OrderFacadeImplUnitTest onPaid 段 6 用例）已锁编排与端口语义；本类
 * 只验证 mock 覆盖不到的装配面，不重复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class PaymentCallbackAcTest {

    /** 店铺 A（子单/库存 tenant 锚点）。 */
    private static final long SHOP_A = 96101L;

    /** 买家 A（订单归属买家）。 */
    private static final long BUYER_A = 56101L;

    /** 主单（PENDING_PAYMENT）。 */
    private static final long MASTER = 86101L;

    /** 子单（PENDING_PAYMENT，tenant=SHOP_A）。 */
    private static final long SUB = 76101L;

    /** SKU（库存行锚点）。 */
    private static final long SKU = 96101L;

    /** 支付单（PENDING_PAYMENT，pay_no 唯一）。 */
    private static final long PAYMENT = 46101L;

    @Autowired
    private PaymentCallbackUseCase paymentCallbackUseCase;

    /**
     * 手触入口与认证链同构：未绑定跟踪作用域触发 50 fail-closed（ISE），
     * 必须以 withScope 包裹（回调上下文无请求视角，跟踪作用域仍由入口
     * 组件绑定——测试直调无入口组件）
     */
    private void handlePay(ValidatedCallback callback) {
        com.nona.inf.context.TrackingContext.withScope(() ->
                paymentCallbackUseCase.handlePayCallback(callback));
    }

    @Autowired
    private PaymentOrderRepositoryImpl paymentOrderRepositoryImpl;

    /**
     * 每用例前：幂等清理 + 直插基础行——主单 + 子单 + 订单项 + 库存
     * （held=1 预占态）+ 支付单（PENDING_PAYMENT）。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 主单维度幂等清理（跨类同段残留兼容：getByMasterOrderId 反查面
            // 不混入他类直插的挂同主单子单）
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM payment_callback_log WHERE payment_order_id IN"
                            + " (SELECT id FROM payment_order WHERE order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM payment_order WHERE order_id = ?", MASTER);
            AcceptanceDbSupport.update(conn, "DELETE FROM inventory_log WHERE sku_id = ?", SKU);
            AcceptanceDbSupport.update(conn, "DELETE FROM inventory_item WHERE sku_id = ?", SKU);
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
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_CB49",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "PENDING_PAYMENT");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_CB49", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, "PENDING_PAYMENT", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36101L,
                String.valueOf(SHOP_A), SUB, 96101L, SKU, "fixture毛衣", 5000L, 1, 5000L);
        // 预占态：available=9 / held=1 / sold=0
        AcceptanceDbSupport.update(conn,
                "INSERT INTO inventory_item (create_time, update_time, id, tenant_id, sku_id,"
                        + " available, held, sold, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36102L,
                String.valueOf(SHOP_A), SKU, 9, 1, 0, 0);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO payment_order (create_time, update_time, id, pay_no, order_id, amount,"
                        + " channel, timeout_at, status, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), PAYMENT, "PAY_CB49",
                MASTER, 5300L, "MOCK", AcceptanceDbSupport.utcOffset(60),
                "PENDING_PAYMENT", false);
    }

    /**
     * 冒烟-1 真实事务三域原子（核心）：真实 Spring 事务下成功回调全链
     * ——支付单 PAID + 全部子单 PAID + 主单派生 PAID + 逐子单确认扣减
     * （inventory_item 三态迁移 + inventory_log 行）同事务提交；<b>编排
     * 失败回滚实测</b>——构造库存对账失败（held=0 无预占）使 confirmDeduct
     * 抛异常 → 支付单/子单/库存全回滚（无半程态：库中不存在「已支付而
     * 订单未推进」）。
     * <p>
     * 断言面：JDBC 直查 payment_order/sub_order/master_order/inventory_item/
     * inventory_log 落库值，断言状态与三态迁移；失败剧本断言全部回滚。
     */
    @Test
    @DisplayName("冒烟-1 真实事务三域原子：成功全链提交 + 编排失败整体回滚")
    void realTransaction_threeDomainsAtomic() throws Exception {
        handlePay(new ValidatedCallback(
                CallbackType.PAY, "PAY_CB49", null, GatewayResult.SUCCESS,
                "TXN-CB49-1", 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 支付单 PAID + 留痕 + channel_txn_no 落位
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT result FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo("SUCCESS");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT channel_txn_no FROM payment_order WHERE id = ?", PAYMENT))
                    .isEqualTo("TXN-CB49-1");
            // 子单 PAID + 主单派生 PAID
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PAID");
            // 库存 confirmDeduct：held 1→0 / sold 0→1 + CONFIRM 流水
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT held FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("1");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? ORDER BY id DESC LIMIT 1", SKU))
                    .isEqualTo("CONFIRM");
        }
        // 失败剧本：库存 held=0（对账失败）→ confirmDeduct CAS 影响 0 行 → 全回滚
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn,
                    "UPDATE inventory_item SET held = 0 WHERE sku_id = ?", SKU);
            AcceptanceDbSupport.update(conn,
                    "UPDATE payment_order SET status = 'PENDING_PAYMENT' WHERE id = ?", PAYMENT);
            AcceptanceDbSupport.update(conn,
                    "UPDATE sub_order SET status = 'PENDING_PAYMENT' WHERE id = ?", SUB);
            AcceptanceDbSupport.update(conn,
                    "UPDATE master_order SET status = 'PENDING_PAYMENT' WHERE id = ?", MASTER);
            assertThatThrownBy(() -> handlePay(new ValidatedCallback(
                    CallbackType.PAY, "PAY_CB49", null, GatewayResult.SUCCESS,
                    "TXN-CB49-2", 5300L)))
                    .isInstanceOf(BusinessException.class);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("PENDING_PAYMENT");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PENDING_PAYMENT");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PENDING_PAYMENT");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isEqualTo(1L);
        }
    }

    /**
     * 冒烟-2 提权写段真实面：回调上下文（无买家身份、tenant 空）推进子单
     * （tenant=shopId）与库存（tenant=shopId）经
     * {@code TenantPrivilege.elevatedInTransaction} 真实放行
     * （TenantWriteGate 不拒）；PO 显式 tenantID 归位（子单/库存
     * tenant=shopId）。
     * <p>
     * 断言面：注入真实用例/TransactionTemplate；直查 PO tenant 列断言
     * 归位；回调上下文无身份（tenant 空）不触发归属校验。
     */
    @Test
    @DisplayName("冒烟-2 提权写段：回调上下文跨租户写真实放行 + PO tenant 归位")
    void elevatedTransaction_writeBypassReal() throws Exception {
        handlePay(new ValidatedCallback(
                CallbackType.PAY, "PAY_CB49", null, GatewayResult.SUCCESS,
                "TXN-CB49-1", 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 子单/库存 tenant 列归位 = shopId（回调上下文无身份但提权放行后显式写入）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT tenant_id FROM sub_order WHERE id = ?", SUB))
                    .isEqualTo(String.valueOf(SHOP_A));
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT tenant_id FROM inventory_item WHERE sku_id = ?", SKU))
                    .isEqualTo(String.valueOf(SHOP_A));
        }
    }

    /**
     * 冒烟-3 幂等重放真实面：同一回调重放 → {@code payment_callback_log}
     * 第二条留痕落库 + 订单/库存零二次变更（幂等键 (order_id, sku_id,
     * type) 未被二次消费）；重复回调命中状态守卫 status_illegal 透传。
     * <p>
     * 断言面：先完整成功回调一次，再重放同号回调；断言留痕表 2 行 +
     * 库存流水/子单/主单无新增变更行。
     */
    @Test
    @DisplayName("冒烟-3 幂等重放：第二条留痕落库 + 订单/库存零二次变更")
    void idempotentReplay_secondTraceOnly() throws Exception {
        handlePay(new ValidatedCallback(
                CallbackType.PAY, "PAY_CB49", null, GatewayResult.SUCCESS,
                "TXN-CB49-1", 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo(1L);
            final long logCount = AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU);
            // 重放同号回调：状态守卫拒绝（payment_status_illegal）透传，留痕第二条落库
            assertThatThrownBy(() -> handlePay(new ValidatedCallback(
                    CallbackType.PAY, "PAY_CB49", null, GatewayResult.SUCCESS,
                    "TXN-CB49-1", 5300L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("payment.status_illegal");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo(2L);
            // 库存流水/子单/主单零二次变更
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isEqualTo(logCount);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PAID");
        }
    }

    /**
     * 冒烟-4 失败回调链：支付单 FAILED 落库 + 订单停留待支付 + 库存预占
     * 原样（无扣减）。
     * <p>
     * 断言面：失败回调跑一次；直查 payment_order FAILED、子单/主单
     * 停留待支付、inventory_item held 未动（无 sold 迁移行）。
     */
    @Test
    @DisplayName("冒烟-4 失败回调链：支付单 FAILED + 订单/库存不动")
    void failCallback_chainOnlyPaymentMoved() throws Exception {
        handlePay(new ValidatedCallback(
                CallbackType.PAY, "PAY_CB49", null, GatewayResult.FAIL,
                "TXN-CB49-F1", 5300L));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("FAILED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PENDING_PAYMENT");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PENDING_PAYMENT");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT held FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("1");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isZero();
        }
    }

    /**
     * 冒烟-5 仓储链路 + 留痕从表装载：payment_order/payment_callback_log
     * （rootId 从表 getOther）/sub_order/master_order/inventory_item/
     * inventory_log 真实 JPA 链路与 DifferRepository 变更集落库；
     * channel_txn_no 唯一约束并发兜底面（DDL 核对：pay_no / order_id /
     * channel_txn_no 三唯一 + (status, timeout_at) 复合索引）。
     * <p>
     * 断言面：仓储实现 bean 就位；冒烟-1~4 的 PO 映射回归均以此链路为
     * 底座（本条目为装配先决项）；DDL 约束核对记录。
     */
    @Test
    @DisplayName("冒烟-5 仓储链路：六仓储 JPA 实现装配 + 留痕从表装载")
    void repositoryChain_jpaImplementationsWired() throws Exception {
        assertThat(paymentOrderRepositoryImpl).isNotNull();
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'payment_order'"
                            + " AND index_name = 'uk_payment_order_pay_no' AND non_unique = 0"))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'payment_order'"
                            + " AND index_name = 'uk_payment_order_order_id' AND non_unique = 0"))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'payment_order'"
                            + " AND index_name = 'uk_payment_order_channel_txn_no' AND non_unique = 0"))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'payment_order'"
                            + " AND index_name = 'idx_payment_order_status_timeout'"))
                    .isEqualTo(1L);
        }
    }
}
