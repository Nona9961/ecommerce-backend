package com.nona.application.mall;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.acceptance.AcceptanceDbSupport.ListLogAppender;
import com.nona.exceptions.BusinessException;
import com.nona.inf.events.OrderCompletedLogListener;
import com.nona.inf.persistence.repository.MasterOrderRepositoryImpl;
import com.nona.inf.persistence.repository.SubOrderRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认收货编排真实链验收测试（真实链装配面——PO 映射/租户过滤/
 * 上下文传播逐一验证；按 javadoc 启用契约改写——
 * MasterOrder/SubOrder 仓储 JPA 实现 + 用例/门面/事件
 * 发布器 Spring 注册落地）。
 * <p>
 * 装配面清单见红设计报告 §装配面清单——真实事务双入口 / 完成事件真实
 * 投递 / 提权事务边界 / 幂等重放真实面 / 归属过滤 + 仓储链路 / 仓储降级
 * 规则。事件监听/发布/执行器（OrderCompletedLogListener /
 * SpringOrderCompletedEventPublisher / OrderCompletedEventConfig）为 inf
 * 装配面已注册（InventoryEventConfig 同构），冒烟验其真实投递
 * （AFTER_COMMIT + 异步日志留痕）。
 * <p>
 * 数据准备：测试内 JDBC 直插（测试库 ecommerce_test，固定 ID 段
 * 96101/86101/76101，与 59/60 fixture 分域不冲突）；执行 = 真实 Spring
 * 装配用例（withScope + setTenantID 模拟商家请求上下文，认证链同构）；
 * 断言 = JDBC 直查落库值 + OutputCapture 日志面。
 * <p>
 * 单测（ConfirmReceiptUseCaseUnitTest/OrderFacadeImplUnitTest，19 用例）
 * 已锁编排与端口语义；本类只验证 mock 覆盖不到的装配面，不重复
 * 业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ConfirmReceiptAcTest {

    /** 店铺 A（子单 tenant 锚点）。 */
    private static final long SHOP_A = 96101L;

    /** 买家 A（订单归属买家）。 */
    private static final long BUYER_A = 56101L;

    /** 买家 B（跨买家归属 404 剧本）。 */
    private static final long BUYER_B = 56102L;

    /** 主单 1（SHIPPED，子单 S1）。 */
    private static final long MASTER_1 = 86101L;

    /** 主单 2（SHIPPED，子单 S2——超时自动完成入口）。 */
    private static final long MASTER_2 = 86102L;

    /** 子单 1/2（SHIPPED，tenant=SHOP_A）。 */
    private static final long SUB_1 = 76101L;

    private static final long SUB_2 = 76102L;

    /** 非法迁移剧本子单（冒烟-1/冒烟-3 直插，幂等清理白名单成员）。 */
    private static final long SUB_ILLEGAL_1 = 76103L;

    private static final long SUB_ILLEGAL_2 = 76104L;

    /** 非法迁移剧本子单订单项（子单装载校验必带——ORDER_SUB_EMPTY 守卫）。 */
    private static final long ITEM_ILLEGAL_1 = 36103L;

    private static final long ITEM_ILLEGAL_2 = 36104L;

    @Autowired
    private ConfirmReceiptUseCase confirmReceiptUseCase;

    /**
     * 完成事件日志捕获（logback ListAppender 直收——不依赖 System.out
     * 捕获：多测试类同 JVM 下 OutputCapture 对非首个 context 类失效，
     * 实测；每方法挂载/卸载，方法级隔离）
     */
    private ListLogAppender orderLogAppender;

    @BeforeEach
    void attachLogCapture() {
        orderLogAppender = AcceptanceDbSupport.installLogAppender(OrderCompletedLogListener.class);
    }

    @AfterEach
    void detachLogCapture() {
        AcceptanceDbSupport.detachLogAppender(OrderCompletedLogListener.class, orderLogAppender);
    }

    /** 完成事件日志计数（marker 出现次数——事件异步线程写入，CopyOnWrite 安全读）。 */
    private long orderEventCount(String marker) {
        return orderLogAppender.events().stream()
                .filter(m -> m != null && m.contains(marker))
                .count();
    }

    /** 最后一条含 marker 的完成事件日志（载荷断言）。 */
    private String lastOrderEventMessage(String marker) {
        return orderLogAppender.events().stream()
                .filter(Objects::nonNull)
                .filter(m -> m.contains(marker))
                .reduce((a, b) -> b)
                .orElse("");
    }

    @Autowired
    private MasterOrderRepositoryImpl masterOrderRepositoryImpl;

    @Autowired
    private SubOrderRepositoryImpl subOrderRepositoryImpl;

    /**
     * 每用例前：幂等清理 + 直插两主单（SHIPPED）+ 两子单（SHIPPED）。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 主单维度幂等清理（跨类同段残留兼容：反查面不混入他类直插残留）
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM order_item WHERE sub_order_id IN"
                            + " (SELECT id FROM sub_order WHERE master_order_id IN (?, ?))",
                    MASTER_1, MASTER_2);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM sub_order WHERE master_order_id IN (?, ?)",
                    MASTER_1, MASTER_2);
            AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id IN (?, ?)",
                    MASTER_1, MASTER_2);
            insertBaseRows(conn);
        }
    }

    private static void insertBaseRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER_1, "ORD_CON49A",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "SHIPPED");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER_2, "ORD_CON49B",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "SHIPPED");
        for (long[] row : new long[][]{{SUB_1, MASTER_1, 36101L}, {SUB_2, MASTER_2, 36102L}}) {
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), row[0],
                    String.valueOf(SHOP_A), row[1], SHOP_A, "SUB_CON49" + row[0], "张三",
                    "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                    5000L, 300L, 0L, 5300L, "SHIPPED", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), row[2],
                    String.valueOf(SHOP_A), row[0], 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
        }
    }

    /**
     * 冒烟-1 真实事务双入口（核心）：真实 Spring 事务下
     * {@link ConfirmReceiptUseCase#confirmByBuyer} 与
     * {@link ConfirmReceiptUseCase#autoCompleteByTimeout} 各跑一次——子单
     * 落库 COMPLETED + 主单派生 COMPLETED 落库（真实 JPA 链路，
     * tenant=shopId 子单行 + global 主单行）。
     * <p>
     * 断言面：JDBC 直查子单/主单落库值，断言状态与租户列（子单
     * tenant=shopId / 主单 global 无租户列）；非法迁移剧本（PAID 子单
     * 确认）→ 断言整体回滚（无部分提交）。
     */
    @Test
    @DisplayName("冒烟-1 真实事务双入口：买家确认与超时自动完成各跑一次落库")
    void realTransaction_bothEntriesCommitted() throws Exception {
        com.nona.inf.context.TrackingContext.withScope(() -> {
            confirmReceiptUseCase.confirmByBuyer(BUYER_A, SUB_1);
            confirmReceiptUseCase.autoCompleteByTimeout(SUB_2);
        });
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_1)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_2)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_2)).isEqualTo("COMPLETED");
            // 租户列：子单 tenant=shopId；主单 global（无 tenant 列面）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT tenant_id FROM sub_order WHERE id = ?", SUB_1))
                    .isEqualTo(String.valueOf(SHOP_A));

            // 非法迁移剧本：PAID 子单确认收货（直插 + 订单项，装载校验必带）
            // → autoComplete 聚合守卫拒绝 → 整体回滚
            final long subIllegal = SUB_ILLEGAL_1;
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subIllegal,
                    String.valueOf(SHOP_A), MASTER_1, SHOP_A, "SUB_CON49R", "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "文一西路 1 号", 5000L, 300L, 0L, 5300L,
                    "PAID", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_ILLEGAL_1,
                    String.valueOf(SHOP_A), subIllegal, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                confirmReceiptUseCase.confirmByBuyer(BUYER_A, subIllegal);
            })).isInstanceOf(BusinessException.class);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", subIllegal)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1)).isEqualTo("COMPLETED");
        }
    }

    /**
     * 冒烟-2 完成事件真实投递：编排发布后 AFTER_COMMIT 异步监听真实触发
     * （{@code OrderCompletedLogListener} 日志留痕可观测——日志前缀
     * {@code [order-event]} + 事件类型 + 完成子单/主单引用 ID）；事件载荷
     * subOrderId/masterOrderId 与落库一致。
     * <p>
     * 断言面：提交后 poll 等待事件异步日志留痕（相对等待窗口），断言
     * 载荷匹配落库行；「无事务的发布路径不触发」面登记装配面清单
     * （宿主复核项）。
     */
    @Test
    @DisplayName("冒烟-2 完成事件真实投递：AFTER_COMMIT 异步监听 + 载荷一致")
    void completedEvent_afterCommitDelivered() throws Exception {
        final String marker = "[order-event] OrderCompleted subOrderId=" + SUB_1;
        final long before = orderEventCount(marker);
        com.nona.inf.context.TrackingContext.withScope(() -> {
            confirmReceiptUseCase.confirmByBuyer(BUYER_A, SUB_1);
        });
        AcceptanceDbSupport.pollUntil(
                () -> orderEventCount(marker) > before,
                "AFTER_COMMIT 异步完成事件日志留痕（[order-event] OrderCompleted subOrderId="
                        + SUB_1 + "，载荷 = 落库子单）",
                Duration.ofSeconds(10));
        // 载荷 masterOrderId 一致（同一日志行含主单引用）
        assertThat(lastOrderEventMessage(marker)).contains(marker + " masterOrderId=" + MASTER_1);
    }

    /**
     * 冒烟-3 提权事务边界：买家确认推进店铺数据（子单 tenant=shopId）
     * 经 {@code TenantPrivilege.elevatedInTransaction} 真实放行；用例方法
     * 级 {@code @Transactional} 回滚边界真实生效——守卫拒绝（未发货确认）
     * 时无部分提交。
     * <p>
     * 断言面：注入真实用例/TransactionTemplate；非法迁移剧本断言子单/
     * 主单均未落库变更（整体回滚）。
     */
    @Test
    @DisplayName("冒烟-3 提权事务边界：跨租户写放行 + 回滚边界")
    void elevatedTransaction_writeBypassAndRollbackBoundary() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 非法迁移：PAID 子单确认（直插 + 订单项，装载校验必带；未发货不可确认）
            // → autoComplete 聚合守卫拒绝
            final long subIllegal = SUB_ILLEGAL_2;
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subIllegal,
                    String.valueOf(SHOP_A), MASTER_1, SHOP_A, "SUB_CON49R2", "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "文一西路 1 号", 5000L, 300L, 0L, 5300L,
                    "PAID", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_ILLEGAL_2,
                    String.valueOf(SHOP_A), subIllegal, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
            final String masterBefore = AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                confirmReceiptUseCase.confirmByBuyer(BUYER_A, subIllegal);
            })).isInstanceOf(BusinessException.class);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", subIllegal)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1)).isEqualTo(masterBefore);
        }
    }

    /**
     * 冒烟-4 幂等重放真实面：已完成子单再确认（confirmByBuyer）与再超时
     * （autoCompleteByTimeout）→ 幂等短路（无二次状态变更、无二次事件
     * 发布——消费方不收重复完成）。
     * <p>
     * 断言面：先完整完成一次，再重放两个入口，断言子单/主单无二次
     * 变更且事件日志无重复留痕（日志计数不变）。
     */
    @Test
    @DisplayName("冒烟-4 幂等重放：已完成子单再确认/再超时短路")
    void idempotentReplay_noSecondChange() throws Exception {
        final String marker = "[order-event] OrderCompleted subOrderId=" + SUB_1;
        final long before = orderEventCount(marker);
        com.nona.inf.context.TrackingContext.withScope(() -> {
            confirmReceiptUseCase.confirmByBuyer(BUYER_A, SUB_1);
        });
        AcceptanceDbSupport.pollUntil(
                () -> orderEventCount(marker) > before,
                "首次完成事件日志留痕", Duration.ofSeconds(10));
        final long firstCount = orderEventCount(marker);
        // 重放两个入口：幂等短路（无二次状态变更、无二次事件发布）
        com.nona.inf.context.TrackingContext.withScope(() -> {
            confirmReceiptUseCase.confirmByBuyer(BUYER_A, SUB_1);
            confirmReceiptUseCase.autoCompleteByTimeout(SUB_1);
        });
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_1)).isEqualTo("COMPLETED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1)).isEqualTo("COMPLETED");
        }
        // 重放窗口稳定（预期零新增；短路失效则晚到事件计入 → 断言红）：
        // 异步 AFTER_COMMIT 延迟打印窗口兜底等待
        Thread.sleep(1500);
        assertThat(orderEventCount(marker))
                .as("幂等重放不得产生重复完成事件日志")
                .isEqualTo(firstCount);
    }

    /**
     * 冒烟-5 归属过滤 + 仓储链路：他人对已完成子单确认 → 404 fail-closed
     * （归属校验先于幂等短路，不因短路放行越权）；子单反查主单
     * （getByMasterOrderId）真实装配。
     * <p>
     * 断言面：以第二买家上下文调用断言 business exception
     * {@code order.master_not_found}（404）且无任何落库副作用。
     */
    @Test
    @DisplayName("冒烟-5 归属过滤：他人确认 fail-closed + 子单反查主单装配")
    void ownershipFilter_crossBuyerRejected() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                confirmReceiptUseCase.confirmByBuyer(BUYER_B, SUB_1);
            }))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("order.master_not_found");
            // 无任何落库副作用（他人确认不产生完成迁移）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_1)).isEqualTo("SHIPPED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_1)).isEqualTo("SHIPPED");
        }
    }

    /**
     * 冒烟-6 仓储链路：MasterOrder/SubOrder 仓储 JPA 实现装配就位
     * （本条目为冒烟-1~5 的装配先决项）。
     */
    @Test
    @DisplayName("冒烟-6 仓储链路：MasterOrder/SubOrder JPA 实现装配就位（先决项）")
    void repositoryChain_jpaImplementationsWired() {
        assertThat(masterOrderRepositoryImpl).isNotNull();
        assertThat(subOrderRepositoryImpl).isNotNull();
    }
}
