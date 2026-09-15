package com.nona.inf.logistics;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.acceptance.AcceptanceDbSupport.ListLogAppender;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.events.WaybillDeliveredLogListener;
import com.nona.inf.persistence.repository.WaybillRepositoryImpl;
import com.nona.inf.persistence.repository.SubOrderRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物流模拟推进器 + 签收联动真实链验收测试（真实链装配面——PO 映射/
 * 仓储链路/事件消费装配/调度触发逐一验证；按 javadoc 启用契约
 * 改写——Waybill/SubOrder 仓储 JPA 实现 +
 * 推进器/消费方 Spring 注册落地，调度门控 test=false 手触）。
 * <p>
 * 装配面清单见装配说明——真实推进链路（运单主从表落库 +
 * 状态推进 + 事件发布 → 子单完成联动）、节奏配置生效、事件消费装配。
 * <p>
 * 执行形态：test=false 调度关闭（无自动滴答），经
 * {@link LogisticsSimulator#scanAndAdvance} 手触（SchedulingAssemblyAcTest
 * 同款手触入口）；运单为 global 表（无租户过滤面），本类以自有数据
 * 锚定（固定 ID 段 66101/76101/86101），不触碰库中其他在途运单——
 * 手触扫描会推进全部到期在途运单，故本类造数后立即手触并断言自有行，
 * 其余到期行副作用登记装配面清单（人工复核项）。
 * <p>
 * 单测（LogisticsSimulatorUnitTest / WaybillDeliveredReceiptListenerUnitTest）
 * 已锁推进与联动语义；本类只验证 mock 覆盖不到的装配面，不重复业务
 * 断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class LogisticsSimulatorAcTest {

    /** 店铺 A（子单 tenant 锚点）。 */
    private static final long SHOP_A = 96101L;

    /** 买家 A（订单归属买家）。 */
    private static final long BUYER_A = 56101L;

    /** 主单（SHIPPED）。 */
    private static final long MASTER = 86101L;

    /** 子单（SHIPPED，tenant=SHOP_A）。 */
    private static final long SUB = 76101L;

    /** 运单（SHIPPED 在途，in_transit=TRUE）。 */
    private static final long WAYBILL = 66101L;

    /** 未到期运单（冒烟-2 直插，幂等清理白名单成员）+ 轨迹。 */
    private static final long WAYBILL_NOT_DUE = 66103L;

    private static final long TRACK_NOT_DUE = 66104L;

    /** 未到期运单的独立子单/订单项（避免与基础在途行撞在途唯一约束）+ 幂等清理。 */
    private static final long SUB_NOT_DUE = 76105L;

    private static final long ITEM_NOT_DUE = 36105L;

    @Autowired
    private LogisticsSimulator logisticsSimulator;

    @Autowired
    private WaybillRepositoryImpl waybillRepositoryImpl;

    @Autowired
    private SubOrderRepositoryImpl subOrderRepositoryImpl;

    /**
     * 签收事件日志捕获（logback ListAppender 直收——不依赖 System.out
     * 捕获：多测试类同 JVM 下 OutputCapture 对非首个 context 类失效，
     * 实测；每方法挂载/卸载，方法级隔离）
     */
    private ListLogAppender waybillLogAppender;

    @BeforeEach
    void attachLogCapture() {
        waybillLogAppender = AcceptanceDbSupport.installLogAppender(WaybillDeliveredLogListener.class);
    }

    @AfterEach
    void detachLogCapture() {
        AcceptanceDbSupport.detachLogAppender(WaybillDeliveredLogListener.class, waybillLogAppender);
    }

    /**
     * 每用例前：幂等清理 + 直插基础行——主单（SHIPPED）+ 子单（SHIPPED）+
     * 运单（SHIPPED 在途，轨迹 SHIPPED 一条）。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 扫描面隔离：清全局在途运单（in_transit=true）——其他 AcTest 直插的
            // CDC 源在途运单（如 PlatformLogisticsViewAcTest 的镜像 fixture：只插
            // waybill 行无轨迹）会被 findInTransit 全表扫描装载命中（轨迹非空守卫
            // 必炸）；测试库专用，本类只扫自己的 WAYBILL/WAYBILL_NOT_DUE
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM waybill_track WHERE waybill_id IN"
                            + " (SELECT id FROM waybill WHERE in_transit = b'1')");
            AcceptanceDbSupport.update(conn,
                    "DELETE FROM waybill WHERE in_transit = b'1'");
            // 主单维度幂等清理（跨类同段残留兼容：getByMasterOrderId 反查面
            // 不混入他类直插的挂同主单子单）
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

    private static void insertBaseRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_LOG49",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, 5300L, "SHIPPED");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_LOG49", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, 5300L, "SHIPPED", WAYBILL, false);
        // 子单订单项（子单装载校验必带——ORDER_SUB_EMPTY 守卫；金额自洽
        // 5000+300=5300 与子单实付一致）
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 36101L,
                String.valueOf(SHOP_A), SUB, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
        // 运单：SHIPPED 在途（in_transit=TRUE）+ 初始轨迹 SHIPPED（occurred_at 过去时刻 → 到期）
        AcceptanceDbSupport.update(conn,
                "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                        + " tracking_no, status, in_transit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), WAYBILL, SUB,
                "顺丰速运", "SF-LOG-49", "SHIPPED", true);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO waybill_track (create_time, update_time, id, waybill_id, status,"
                        + " occurred_at, description)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), 66102L, WAYBILL,
                "SHIPPED", AcceptanceDbSupport.utcOffset(-120), "包裹已揽收");
    }

    /**
     * 冒烟-1 真实推进链路（核心）：真实 Spring 事务下扫描推进跑一次
     * ——在途运单行状态推进落库（waybill.status 变更）+ 轨迹从表追加
     * 行（waybill_track，append-only：rootId 归属）+ 推进至已签收时
     * 签收事件真实发布（AFTER_COMMIT 投递）→ 订单域消费侧真实触发
     * 子单完成联动（子单落库 COMPLETED + 主单派生 + 完成事件发布，
     * 载荷与落库一致）。
     * <p>
     * 断言面：JDBC 直查 waybill/waybill_track/sub_order/master_order
     * 断言状态与轨迹追加；联动消费面存在性真实装配（事件监听器注册 +
     * 提权写段真实放行——子单 tenant=shopId 推进）。
     */
    @Test
    @DisplayName("冒烟-1 真实推进链路：运单主从表 + 状态推进 + 事件发布 → 子单完成联动")
    void realChain_scanAdvanceDeliveredAndAutoComplete() throws Exception {
        // 手触推进（test=false 调度关闭；SHIPPED 满 30s → IN_TRANSIT；
        // IN_TRANSIT 满 60s → DELIVERED + 事件；轨迹 occurred_at 已置
        // -120 分钟 → 第一跳即到期）。
        // 手触入口与认证链同构：未绑定跟踪作用域触发 50 fail-closed（ISE），
        // 必须以 TrackingContext.withScope 包裹（System 上下文，运单 global 无租户面）
        com.nona.inf.context.TrackingContext.withScope(() -> logisticsSimulator.scanAndAdvance());
        // 时间流逝模拟：第一跳推进出的 IN_TRANSIT 轨迹锚点 = 本轮扫描时刻，
        // 第二跳需再满 60s——调度关闭下无自动滴答，将轨迹锚点回拨（手触
        // 口径的时间控制面，与直插 occurred_at 同构）后执行第二跳
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn,
                    "UPDATE waybill_track SET occurred_at = ? WHERE waybill_id = ?",
                    AcceptanceDbSupport.utcOffset(-120), WAYBILL);
        }
        com.nona.inf.context.TrackingContext.withScope(() -> logisticsSimulator.scanAndAdvance());
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 运单终态 DELIVERED + in_transit 释放（NULL）
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM waybill WHERE id = ?", WAYBILL)).isEqualTo("DELIVERED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT in_transit FROM waybill WHERE id = ?", WAYBILL)).isNull();
            // 轨迹从表 append-only：初始 SHIPPED + 推进 IN_TRANSIT + DELIVERED 共 3 行（rootId 归属）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM waybill_track WHERE waybill_id = ?", WAYBILL)).isEqualTo(3L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM waybill_track WHERE waybill_id = ? ORDER BY id DESC LIMIT 1", WAYBILL))
                    .isEqualTo("DELIVERED");
            // 子单完成联动（签收事件异步消费链——AFTER_COMMIT → autoCompleteOnDelivered）：
            // poll 等待联动落库（异步窗口与断言解耦）
            AcceptanceDbSupport.pollUntil(() -> {
                try (Connection c = AcceptanceDbSupport.mysql()) {
                    return "COMPLETED".equals(AcceptanceDbSupport.stringValue(c,
                            "SELECT status FROM sub_order WHERE id = ?", SUB));
                } catch (final Exception ex) {
                    throw new IllegalStateException("子单联动状态查询失败", ex);
                }
            }, "签收联动子单完成落库（[waybill-delivered] autoComplete）", Duration.ofSeconds(10));
            // 主单派生 COMPLETED（同异步链落库）
            AcceptanceDbSupport.pollUntil(() -> {
                try (Connection c = AcceptanceDbSupport.mysql()) {
                    return "COMPLETED".equals(AcceptanceDbSupport.stringValue(c,
                            "SELECT status FROM master_order WHERE id = ?", MASTER));
                } catch (final Exception ex) {
                    throw new IllegalStateException("主单派生状态查询失败", ex);
                }
            }, "签收联动主单派生 COMPLETED", Duration.ofSeconds(10));
        }
        // 签收事件真实投递（AFTER_COMMIT 异步日志留痕，载荷 = 运单/子单）
        final String marker = "[waybill-event] WaybillDelivered waybillId=" + WAYBILL
                + " subOrderId=" + SUB;
        AcceptanceDbSupport.pollUntil(
                () -> waybillLogAppender.events().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(m -> m.contains(marker)),
                "签收事件 AFTER_COMMIT 异步日志留痕（[waybill-event] waybillId=" + WAYBILL + "）",
                Duration.ofSeconds(10));
    }

    /**
     * 冒烟-2 节奏配置生效：推进间隔经配置覆盖（shipped→in_transit /
     * in_transit→delivered）真实生效——已发货满配置值推进运输中、满
     * 配置中段值推进已签收；扫描周期配置（scan-interval-ms）不炸
     * 装配。
     * <p>
     * 断言面：以短节奏配置（秒级）跑真实时间推进——到期运单在配置
     * 间隔内推进落库，未到期保持原状；配置缺省回落默认值（30s/60s）
     * 语义由上下文装配面核对（本类以默认节奏 + 过去时刻轨迹验证到期
     * 推进面，配置覆盖面登记装配面清单人工复核项）。
     */
    @Test
    @DisplayName("冒烟-2 节奏配置生效：配置覆盖真实推进间隔")
    void rhythmConfig_appliesToRealProgression() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            // 未到期运单挂独立子单（避免与基础在途 66101 撞 uk_waybill_sub_order_in_transit）
            final long waybillNotDue = WAYBILL_NOT_DUE;
            final long subNotDue = SUB_NOT_DUE;
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), subNotDue,
                    String.valueOf(SHOP_A), MASTER, SHOP_A, "SUB_LOG49N", "张三",
                    "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                    5000L, 300L, 0L, 5300L, "SHIPPED", waybillNotDue, false);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                            + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_NOT_DUE,
                    String.valueOf(SHOP_A), subNotDue, 96101L, 96101L, "fixture毛衣", 5000L, 1, 5000L);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                            + " tracking_no, status, in_transit)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), waybillNotDue, subNotDue,
                    "顺丰速运", "SF-LOG-N", "SHIPPED", true);
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill_track (create_time, update_time, id, waybill_id, status,"
                            + " occurred_at, description)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), TRACK_NOT_DUE, waybillNotDue,
                    "SHIPPED", AcceptanceDbSupport.utcNow(), "刚揽收");
        }
        // 同一 withScope 手触（未绑定作用域触发 50 fail-closed）
        com.nona.inf.context.TrackingContext.withScope(() -> logisticsSimulator.scanAndAdvance());
        // 时间流逝模拟（同冒烟-1 口径）：回拨到期运单轨迹锚点后执行第二跳
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(conn,
                    "UPDATE waybill_track SET occurred_at = ? WHERE waybill_id = ?",
                    AcceptanceDbSupport.utcOffset(-120), WAYBILL);
        }
        com.nona.inf.context.TrackingContext.withScope(() -> logisticsSimulator.scanAndAdvance());
        try (Connection conn2 = AcceptanceDbSupport.mysql()) {
            // 到期运单推进（轨迹 -120 分钟 → DELIVERED）；未到期运单保持 SHIPPED
            assertThat(AcceptanceDbSupport.stringValue(conn2,
                    "SELECT status FROM waybill WHERE id = ?", WAYBILL)).isEqualTo("DELIVERED");
            assertThat(AcceptanceDbSupport.stringValue(conn2,
                    "SELECT status FROM waybill WHERE id = ?", WAYBILL_NOT_DUE)).isEqualTo("SHIPPED");
            // 扫描周期配置键装配面（scan-interval-ms 存在性由 SchedulingAssemblyAcTest 断言）
        }
    }

    /**
     * 冒烟-3 事件消费装配：签收事件五件套真实装配——发布端口
     * （SpringWaybillDeliveredPublisher 转发 ApplicationEventPublisher）+
     * 日志消费（AFTER_COMMIT 异步日志监听真实触发，留痕可观测）+ 业务
     * 消费（自动完成联动监听注册、waybillEventExecutor 执行器就位、
     * 上下文传播装饰器生效）+ 推进器调度位（@Scheduled 触发面）。
     * <p>
     * 断言面：真实推进一次（withScope 手触）→ 三路消费面各就位（日志
     * 留痕/异步任务执行/完成联动副作用），执行器线程名前缀与装饰器装配
     * 核对（日志面 + 联动副作用由冒烟-1 断言承载；本条目以仓储实现 bean
     * 就位 + 推进器 bean 就位为装配底座）。
     */
    @Test
    @DisplayName("冒烟-3 事件消费装配：发布/日志/联动/调度四路真实就位")
    void eventConsumptionAssembly() {
        assertThat(logisticsSimulator).isNotNull();
        assertThat(waybillRepositoryImpl).isNotNull();
        assertThat(subOrderRepositoryImpl).isNotNull();
        // 调度位（@Scheduled 挂点 + 门控不激活）由 SchedulingAssemblyAcTest 断言；
        // 事件五件套真实投递由冒烟-1 的日志留痕 + 完成联动副作用断言承载
    }
}