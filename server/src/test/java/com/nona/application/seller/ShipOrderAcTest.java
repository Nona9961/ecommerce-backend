package com.nona.application.seller;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家发货编排真实链验收测试（真实链装配面——PO 映射/租户过滤/
 * 上下文传播/仓储链路逐一验证；按 javadoc 启用契约改写——
 * 五交易域仓储实现 + 用例/门面 Spring 注册落地）。
 * <p>
 * 装配面清单：真实事务发货 / 运单从表落库 /
 * 在途唯一约束 / 提权事务边界 / 租户过滤兜底 / 仓储降级规则。
 * <p>
 * 数据准备：测试内 JDBC 直插（测试库 ecommerce_test，固定 ID 段
 * 96101/86101/76101/66101，与 59/60 fixture 分域不冲突）；执行 = 真实
 * Spring 装配用例（withScope + setTenantID 模拟商家请求上下文，认证链
 * 同构）；断言 = JDBC 直查落库值（同一 MySQL 测试库）。
 * <p>
 * 单测（ShipOrderUseCaseUnitTest）已锁编排与端口语义；本类只验证
 * mock 覆盖不到的装配面，不重复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ShipOrderAcTest {

    /** 店铺 A（子单 tenant 锚点，商家请求租户）。 */
    private static final long SHOP_A = 96101L;

    /** 店铺 B（跨店 fail-closed 剧本操作者）。 */
    private static final long SHOP_B = 96102L;

    /** 主单（PAID，双子单——部分发货/全部发货派生面）。 */
    private static final long MASTER = 86101L;

    /** 子单 A/B（归属主单，tenant=SHOP_A）。 */
    private static final long SUB_A = 76101L;

    private static final long SUB_B = 76102L;

    /** 回滚剧本子单（冒烟-1/冒烟-3 直插，幂等清理白名单成员）。 */
    private static final long SUB_RB_1 = 76103L;

    private static final long SUB_RB_2 = 76104L;

    /** 回滚剧本子单订单项（子单装载校验必带——ORDER_SUB_EMPTY 守卫）。 */
    private static final long ITEM_RB_1 = 36103L;

    private static final long ITEM_RB_2 = 36104L;

    /** 直插造数主键区间（幂等清理白名单：子单 4 + 订单项 4）。 */
    private static final long[] OWN_IDS = {MASTER, SUB_A, SUB_B, SUB_RB_1, SUB_RB_2,
            36101L, 36102L, ITEM_RB_1, ITEM_RB_2};

    @Autowired
    private ShipOrderUseCase shipOrderUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 每用例前：幂等清理本类造数（从表先于主表）+ 直插基础行——主单
     * （PAID）+ 双子单（PAID，tenant=SHOP_A）+ 订单项（库存回滚/派生
     * 装载面）。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 主单维度幂等清理（跨类同段残留兼容）：子单/订单项/运单按归属
            // 主单反查全清（getByMasterOrderId 反查面不混入他类直插残留）
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM waybill_track WHERE waybill_id IN"
                            + " (SELECT id FROM waybill WHERE sub_order_id IN (SELECT id FROM sub_order"
                            + " WHERE master_order_id = ?))", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM waybill WHERE sub_order_id IN"
                            + " (SELECT id FROM sub_order WHERE master_order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM order_item WHERE sub_order_id IN"
                            + " (SELECT id FROM sub_order WHERE master_order_id = ?)", MASTER);
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM sub_order WHERE master_order_id = ?", MASTER);
            AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id = ?", MASTER);
            insertBaseRows(conn);
        }
    }

    /** 主单 + 双子单 + 订单项直插（PAID 态；金额分：商品 5000 + 运费 300 = 5300）。 */
    private static void insertBaseRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_SHIP49",
                56101L, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "PAID");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB_A, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_SHIP49A", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, "PAID", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB_B, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_SHIP49B", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, "PAID", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36101L,
                String.valueOf(SHOP_A), SUB_A, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36102L,
                String.valueOf(SHOP_A), SUB_B, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
    }

    /**
     * 冒烟-1 真实事务发货全链路（核心）：真实 Spring 事务下
     * {@link ShipOrderUseCase#shipByMerchant} 跑一次——waybill 主表行
     * （subOrderId/company/trackingNo/status=PENDING_SHIPMENT，tenant
     * global）+ waybill_track 初始轨迹行（append-only：waybill_id
     * rootId 关联）+ 子单落库 SHIPPED + waybill_id 定型 + 主单派生
     * （全部已发货 → 主单 SHIPPED；多子单部分发货 → PARTIALLY_SHIPPED）。
     * <p>
     * 断言面：JDBC 直查三表落库值 + 租户列（子单 tenant=shopId / 运单
     * global）+ 主单派生；回滚剧本（未支付子单发货被聚合守卫拒绝）→
     * 断言整体回滚（无部分提交——不出现「运单已建但子单未推进」）。
     */
    @Test
    @DisplayName("冒烟-1 真实事务发货：运单主从表 + 子单状态 + 主单派生同事务落库")
    void realTransaction_shipCommitted() throws Exception {
        final Long waybillId;
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            com.nona.inf.context.TrackingContext.withScope(() -> {
                com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
                shipOrderUseCase.shipByMerchant(SHOP_A, SUB_A, "顺丰速运", "SF-49-001");
            });
            waybillId = Long.valueOf(AcceptanceDbSupport.stringValue(conn,
                    "SELECT id FROM waybill WHERE sub_order_id = ?", SUB_A));
            // 运单主表行：global（无租户列）+ PENDING_SHIPMENT 初态 + 在途位 TRUE
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM waybill WHERE id = ?", waybillId))
                    .isEqualTo("PENDING_SHIPMENT");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT company FROM waybill WHERE id = ?", waybillId)).isEqualTo("顺丰速运");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT tracking_no FROM waybill WHERE id = ?", waybillId)).isEqualTo("SF-49-001");
            // 运单轨迹从表初始行（append-only：rootId 关联）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill_track WHERE waybill_id = ?", waybillId)).isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM waybill_track WHERE waybill_id = ?", waybillId))
                    .isEqualTo("PENDING_SHIPMENT");
            // 子单 SHIPPED + waybill_id 定型 + tenant 列（tenant=shopId）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_A)).isEqualTo("SHIPPED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT waybill_id FROM sub_order WHERE id = ?", SUB_A))
                    .isEqualTo(String.valueOf(waybillId));
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT tenant_id FROM sub_order WHERE id = ?", SUB_A))
                    .isEqualTo(String.valueOf(SHOP_A));
            // 主单派生：双子单仅发一单 → PARTIALLY_SHIPPED
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER))
                    .isEqualTo("PARTIALLY_SHIPPED");
        }
        // 全部发货 → 主单 SHIPPED
        com.nona.inf.context.TrackingContext.withScope(() -> {
            com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            shipOrderUseCase.shipByMerchant(SHOP_A, SUB_B, "顺丰速运", "SF-49-002");
        });
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER))
                    .isEqualTo("SHIPPED");
        }
        // 回滚剧本：未支付子单（PENDING_PAYMENT 直插 + 订单项，子单装载校验必带）发货
        // → markShipped 聚合守卫拒绝 → 整体回滚（无运单行 / 子单状态未变）
        final long subRollback = SUB_RB_1;
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subRollback,
                    String.valueOf(SHOP_A), MASTER, SHOP_A, "SUB_SHIP49R", "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "文一西路 1 号", 5000L, 300L, 0L, 5300L,
                    "PENDING_PAYMENT", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_RB_1,
                    String.valueOf(SHOP_A), subRollback, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
                shipOrderUseCase.shipByMerchant(SHOP_A, subRollback, "顺丰速运", "SF-49-R");
            })).isInstanceOf(BusinessException.class);
            // 整体回滚：无运单行（不出现「运单已建但子单未推进」半程态）+ 子单状态未变
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill WHERE sub_order_id = ?", subRollback)).isZero();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", subRollback))
                    .isEqualTo("PENDING_PAYMENT");
        }
    }

    /**
     * 冒烟-2 运单在途唯一约束（数据库兜底面）：同子单重复发货到 DB 层
     * 被唯一约束拒绝（waybill.sub_order_id 在途唯一——并发窗口兜底，
     * 编排层 409 之外的最后一层防线；约束随仓储接线 DDL 落位）。
     * <p>
     * 断言面：直插第二张同 subOrderId 且 in_transit=TRUE 的运单行 →
     * 唯一约束拒绝（SQLIntegrityConstraintViolationException）；
     * 约束命名核对（uk_waybill_sub_order_in_transit）+ in_transit NULL
     * 历史行多行放行（签收释放锚点语义）。
     */
    @Test
    @DisplayName("冒烟-2 在途唯一约束：同子单第二张在途运单 DB 层拒绝 + 约束命名核对")
    void inTransitUniqueConstraint_dbEnforced() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 第一张在途运单（SUB_A，in_transit=TRUE）
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                            + " tracking_no, status, in_transit)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 66101L,
                    SUB_A, "顺丰速运", "SF-49-001", "SHIPPED", true);
            // 同子单第二张在途运单 → 唯一约束拒绝
            assertThatThrownBy(() -> AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                            + " tracking_no, status, in_transit)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 66102L,
                    SUB_A, "中通快递", "ZT-49-001", "SHIPPED", true))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            // in_transit NULL（历史行）多行放行——签收释放锚点语义
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                            + " tracking_no, status, in_transit)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 66103L,
                    SUB_A, "顺丰速运", "SF-49-H", "DELIVERED", null);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill WHERE sub_order_id = ?", SUB_A)).isEqualTo(2L);
            // 约束命名核对（information_schema.statistics，non_unique=0；
            // 复合索引每列一行 → 按 DISTINCT index_name 收敛）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                            + " WHERE table_schema = 'ecommerce_test' AND table_name = 'waybill'"
                            + " AND index_name = 'uk_waybill_sub_order_in_transit' AND non_unique = 0"))
                    .isEqualTo(1L);
        }
    }

    /**
     * 冒烟-3 租户过滤兜底 + 提权事务边界：商家上下文（SHOP_B）发货
     * SHOP_A 子单 → 真实 TenantContextAccessor 租户过滤（fail-closed）
     * 下装载按不存在呈现 404；本店发货经
     * {@code TenantPrivilege.elevatedInTransaction} 真实放行（子单
     * tenant=shopId 推进）；推进守卫拒绝时整体回滚（无部分提交）。
     * <p>
     * 断言面：注入真实用例/TransactionTemplate，以真实商家请求上下文
     * （scope 填充 shopId）跑两剧本——跨店 404（order.sub_not_found，
     * 无运单副作用）+ 本店失败回滚（未支付子单发货 → 无部分提交）。
     */
    @Test
    @DisplayName("冒烟-3 租户过滤兜底与提权事务边界：跨店 404 + 本店回滚")
    void tenantFilterAndElevatedTransactionBoundary() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 跨店剧本：SHOP_B 上下文发货 SHOP_A 子单 → 租户过滤 fail-closed（404）
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_B));
                shipOrderUseCase.shipByMerchant(SHOP_B, SUB_A, "顺丰速运", "SF-49-X");
            }))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("order.sub_not_found");
            // 无任何落库副作用
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill WHERE sub_order_id = ?", SUB_A)).isZero();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_A)).isEqualTo("PAID");

            // 本店失败回滚剧本：未支付子单发货（直插 + 订单项，装载校验必带）→ 聚合守卫拒绝 → 无部分提交
            final long subRollback = SUB_RB_2;
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subRollback,
                    String.valueOf(SHOP_A), MASTER, SHOP_A, "SUB_SHIP49R2", "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "文一西路 1 号", 5000L, 300L, 0L, 5300L,
                    "PENDING_PAYMENT", null, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_RB_2,
                    String.valueOf(SHOP_A), subRollback, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
            assertThatThrownBy(() -> com.nona.inf.context.TrackingContext.withScope(() -> {
                com.nona.inf.context.TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
                shipOrderUseCase.shipByMerchant(SHOP_A, subRollback, "顺丰速运", "SF-49-R");
            })).isInstanceOf(BusinessException.class);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill WHERE sub_order_id = ?", subRollback)).isZero();
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", subRollback))
                    .isEqualTo("PENDING_PAYMENT");
        }
    }
}
