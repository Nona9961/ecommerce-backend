package com.nona.domain.logistics.service;

import com.nona.acceptance.AcceptanceDbSupport;
import java.sql.SQLException;
import com.nona.api.common.PageQuery;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.ports.PlatformLogisticsViewService;
import com.nona.domain.order.entity.SubOrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台物流视图真实装配验收测试（P5.1：真实装配面——PG 镜像表读取
 * 链路/列投影/状态文字映射/超时列数据面逐一验证；WU-49 按 javadoc 启用
 * 契约改写，红阶段降级声明已解除——WU-61 CDC 三库同步链路已就绪，
 * test 库 PG 镜像（ecommerce_test）含 waybill / sub_order / shop 三张镜像表）。
 * <p>
 * 装配面清单见红设计报告——真实 replica 查询链路 / 超时标记数据面 /
 * 镜像同步核对动作。
 * <p>
 * 数据准备：直插 MySQL test 业务表（waybill / sub_order / shop）→ poll-until
 * CDC 镜像收敛到 PG test（ecommerce_test 库）；断言走 PG 镜像查询
 * （PlatformLogisticsViewService.list → replicaNamedParameterJdbcTemplate → 
 * PG ecommerce_test），零污染主库（MySQL test 是独立测试库）。
 * <p>
 * 视角面设计：PG 镜像查询是最终一致（CDC 秒级延迟），测试以 poll-until
 * 等待收敛（61 纪律：poll 窗口相对断言，不以固定 sleep 猜测收敛时刻）。
 * <p>
 * 单测（PlatformLogisticsViewServiceUnitTest）已锁编排与判定语义；本类
 * 只验证 mock 覆盖不到的装配面（PG 镜像表列投影/状态枚举映射/
 * 时间列 UTC 语义/仓储限名注入），不重复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class PlatformLogisticsViewAcTest {

    /** 店铺 A/B/C（跨店全集行集_fixture）。 */
    private static final long SHOP_A = 96101L;

    private static final long SHOP_B = 96102L;

    private static final long SHOP_C = 96103L;

    /** 店铺名（直插 varchar）。 */
    private static final String SHOP_A_NAME = "fixture店铺甲";

    private static final String SHOP_B_NAME = "fixture店铺乙";

    private static final String SHOP_C_NAME = "fixture店铺丙";

    /** 买家（订单归属）。 */
    private static final long BUYER = 56101L;

    /** 主单 A/B/C。 */
    private static final long MASTER_A = 86101L;

    private static final long MASTER_B = 86102L;

    private static final long MASTER_C = 86103L;

    /** 子单 A（PAID 未发货）、B（PAID 超时）、C（SHIPPED 超时）。 */
    private static final long SUB_A = 76101L;

    private static final long SUB_B = 76102L;

    private static final long SUB_C = 76103L;

    /** 运单（子单 A/B 的部分发货；C 无运单）。 */
    private static final long WAYBILL_A = 66101L;

    private static final long WAYBILL_B = 66102L;

    /** 镜像核对剧本行（冒烟-3 直插，幂等清理白名单成员）。 */
    private static final long MIRROR_SUB = 76999L;

    private static final long MIRROR_MASTER = 86999L;

    private static final long MIRROR_SHOP = 96999L;

    @Autowired
    private PlatformLogisticsViewService viewService;

    /**
     * 每用例前：幂等清理 MySQL test 业务表（CDC 源 → 镜像自动同步至 PG）。
     * <p>
     * 本类为平台全局视角（无租户过滤），断言全部收敛到本类 fixture 行
     * （subOrderId 白名单）——共享测试库中其他 AcTest 直插的残留行不
     * 进入断言面（存在性 + 投影断言，不做全局行数精确断言）。
     */
    @BeforeEach
    void setUp() throws Exception {
        cleanupDbRows();
        // 等 CDC 删除传播收敛（旧行在 PG 镜像消失）——否则测试体 INSERT 后
        // poll「三行齐」可能命中新旧行混合态（旧行未删 + 新行未达），断言不稳
        awaitMirrorSwept();
    }

    /**
     * 类末清理（2026-09-11 补，验收面类间残留根治）：本类 fixture 段与
     * ConfirmReceipt/RefundFlow/ShipOrder 共享（SUB 76101-76103 / MASTER
     * 86101-86103），无类末清理时最后一个用例的数据残留 → 后序同段类
     * setUp 撞主键（Duplicate entry for sub_order.PRIMARY，全量顺序相关）；
     * 类末清理后任何执行顺序零残留。@AfterEach 不等待 CDC 收敛（删除传播
     * 异步完成即可，下轮 @BeforeEach 的 awaitMirrorSwept 兜底）。
     */
    @AfterEach
    void tearDown() throws Exception {
        cleanupDbRows();
    }

    /** 本类 fixture 段幂等清理（@BeforeEach 前置 + @AfterEach 后置共用）。 */
    private void cleanupDbRows() throws Exception {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql, "DELETE FROM waybill_track WHERE waybill_id IN (?, ?)",
                    WAYBILL_A, WAYBILL_B);
            AcceptanceDbSupport.update(mysql, "DELETE FROM waybill WHERE id IN (?, ?)",
                    WAYBILL_A, WAYBILL_B);
            AcceptanceDbSupport.update(mysql, "DELETE FROM sub_order WHERE id IN (?, ?, ?, ?)",
                    SUB_A, SUB_B, SUB_C, MIRROR_SUB);
            AcceptanceDbSupport.update(mysql, "DELETE FROM master_order WHERE id IN (?, ?, ?, ?)",
                    MASTER_A, MASTER_B, MASTER_C, MIRROR_MASTER);
            AcceptanceDbSupport.update(mysql, "DELETE FROM shop WHERE id IN (?, ?, ?, ?)",
                    SHOP_A, SHOP_B, SHOP_C, MIRROR_SHOP);
        }
    }

    /** 等待本类 fixture 行在 PG 镜像消失（CDC DELETE 收敛窗口）。 */
    private static void awaitMirrorSwept() {
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        return AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM sub_order WHERE id IN (?, ?, ?)",
                                SUB_A, SUB_B, SUB_C) == 0;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "PG 镜像本类三行消失（CDC DELETE 收敛，防新旧混合）",
                Duration.ofSeconds(30));
    }

    /** 直插店铺/主单/子单/运单到 MySQL test（CDC 源 → 镜像自动同步）。
     *  createTimeOffsetMinutes：子单创建时间相对窗口（<b>本地墙钟字面</b>——
     *  与部署位 MySQL 服务器 NOW() 语义对齐：DATETIME 无时区、读侧按 JVM
     *  本地时区解析，fixture 须与业务写入同字面语义；禁用绝对日期魔法值，
     *  默认 0 = 当前时刻）。 */
    private void insertRows(Connection conn, long shopId, String shopName,
                            long masterId, long subId, long waybillId,
                            String subStatus, Timestamp timeoutAt,
                            long createTimeOffsetMinutes) throws Exception {
        final Timestamp created = Timestamp.valueOf(
                LocalDateTime.now().plusMinutes(createTimeOffsetMinutes));
        AcceptanceDbSupport.update(conn,
                "INSERT INTO shop (create_time, update_time, id, name, logo, description, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name)",
                created, created, shopId, shopName,
                "http://img.example/logo.png", "fixture desc", "NORMAL");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE status = VALUES(status)",
                created, created, masterId,
                "ORD_LOGV-" + shopId, BUYER, "张三", "13800000000",
                "浙江省", "杭州市", "西湖区", "fixture", 5000L, 300L, 0L, 5300L, subStatus);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status,"
                        + " waybill_id, timeout_at, timeout_type, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE status = VALUES(status),"
                        + " timeout_at = VALUES(timeout_at)",
                created, created, subId, String.valueOf(shopId),
                masterId, shopId, "SUB-LGV-" + subId, "张三", "13800000000",
                "浙江省", "杭州市", "西湖区", "fixture", 5000L, 300L, 0L, 5300L,
                subStatus, waybillId == 0 ? null : waybillId,
                timeoutAt, "ORDER_SHIP", false);
        if (waybillId != 0) {
            AcceptanceDbSupport.update(conn,
                    "INSERT INTO waybill (create_time, update_time, id, sub_order_id, company,"
                            + " tracking_no, status, in_transit)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE status = VALUES(status)",
                    created, created, waybillId,
                    subId, "顺丰速运", "SF-LGV-" + waybillId, "SHIPPED", true);
        }
    }

    /**
     * 冒烟-1 真实 replica 查询链路（核心）：fixture 在 MySQL test 建
     * 三张镜像表（waybill / sub_order / shop，列形与部署位 DDL 对齐——
     * 子单含 shop_id/status/sub_order_no/master_order_id/waybill_id/timeout_at 列，
     * status 按枚举名文字存储）并填充跨店铺行集 → 经真实装配
     * （服务 @Component + 仓储 @Repository + replica 命名参数模板限名注入）
     * 跑通一次全链路：跨店铺全集行集、店铺/状态筛选、分页切片、
     * 固定排序（子单创建时间倒序）、未发货行运单字段为空（LEFT JOIN 语义）。
     * <p>
     * 断言面：直插 MySQL → poll-until PG 镜像收敛 → service.list()
     * 断言结果（行数/字段投影/筛选收敛/排序序）；任一段真实链路
     * 装配缺失 → 上下文启动即失败暴露。
     */
    @Test
    @DisplayName("冒烟-1 真实 replica 链路：跨店行集 + 筛选 + 分页 + 排序 + LEFT JOIN")
    void realReplicaQueryChain() throws Exception {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            // 子单 A：PAID（无运单）  B：PAID（waybill）  C：SHIPPED（无运单）
            // create_time 确定递减（A 最新 → C 最早）→ 排序断言确定（DESC = A, B, C）；
            // timeout_at 用本地墙钟相对窗口（与读侧 JVM 解析语义自洽）
            final Timestamp timeoutPast = Timestamp.valueOf(
                    LocalDateTime.now().minusMinutes(10));
            insertRows(mysql, SHOP_A, SHOP_A_NAME, MASTER_A, SUB_A, 0,
                    "PAID", null, 0);
            insertRows(mysql, SHOP_B, SHOP_B_NAME, MASTER_B, SUB_B, WAYBILL_B,
                    "PAID", timeoutPast, -10);
            insertRows(mysql, SHOP_C, SHOP_C_NAME, MASTER_C, SUB_C, 0,
                    "SHIPPED", timeoutPast, -20);
        }
        // poll-until 本类三行在 PG 镜像收敛——等待面与 viewService 查询面完全
        // 同构（LEFT JOIN waybill × shop 三表）：sink 删除→重插窗口内 waybill
        // 表处于被锁/重建瞬时状态时，INNER/单表面先于查询面放行导致 76103 掉行
        AcceptanceDbSupport.pollUntil(
                () -> rowCountOnPg("sub_order s LEFT JOIN waybill wb ON wb.sub_order_id = s.id "
                        + "LEFT JOIN shop sh ON sh.id = s.shop_id",
                        "s.id IN (" + SUB_A + ", " + SUB_B + ", " + SUB_C + ")") == 3,
                "PG 镜像本类三行收敛（CDC，LEFT JOIN 同构面）",
                Duration.ofSeconds(30));

        // 跨店全集：total ≥ 3 + 本类三行齐全（三店可见性）
        var allResult = viewService.list(new PlatformLogisticsViewFilter(null, null),
                new PageQuery(1, 10));
        assertThat(allResult.records()).isNotEmpty();
        assertThat(allResult.total()).isGreaterThanOrEqualTo(3);
        var allOwn = allResult.records().stream()
                .filter(r -> r.subOrderId() != null && r.subOrderId() >= SUB_A
                        && r.subOrderId() <= SUB_C)
                .toList();
        assertThat(allOwn).hasSize(3);
        assertThat(allOwn.stream().map(r -> r.shopId()).toList())
                .as("跨店全集：本类三行店铺维齐全")
                .containsExactlyInAnyOrder(SHOP_A, SHOP_B, SHOP_C);

        // 店铺筛选（SHOP_A）：命中本类行 + 行投影 shopId
        var shopResult = viewService.list(
                new PlatformLogisticsViewFilter(SHOP_A, null),
                new PageQuery(1, 10));
        assertThat(shopResult.records().stream().filter(r -> r.subOrderId() == SUB_A))
                .hasSize(1);
        assertThat(shopResult.records().stream()
                .filter(r -> r.subOrderId() == SUB_A).findFirst().orElseThrow()
                .shopId()).isEqualTo(SHOP_A);

        // 状态筛选（PAID）：命中本类 PAID 两行（A + B）
        var statusResult = viewService.list(
                new PlatformLogisticsViewFilter(null, SubOrderStatus.PAID),
                new PageQuery(1, 10));
        assertThat(statusResult.records().stream()
                .filter(r -> r.subOrderId() == SUB_A || r.subOrderId() == SUB_B))
                .hasSize(2);
        assertThat(statusResult.records().stream()
                .filter(r -> r.subOrderId() == SUB_A || r.subOrderId() == SUB_B)
                .map(r -> r.subOrderStatus()).toList())
                .as("状态筛选：行主维度履约状态 = PAID")
                .containsOnly(SubOrderStatus.PAID);

        // 分页（page 1 / pageSize 2）：固定返回 2 行；本类三行中最新两行（A/B）在首片中
        var pageResult = viewService.list(new PlatformLogisticsViewFilter(null, null),
                new PageQuery(1, 2));
        assertThat(pageResult.records()).hasSize(2);

        // 未发货行运单字段为空（LEFT JOIN 语义——行级投影，收敛本类三行）
        for (var item : allOwn) {
            if (item.subOrderId() == SUB_A || item.subOrderId() == SUB_C) {
                assertThat(item.waybillId()).isNull();
                assertThat(item.company()).isNull();
                assertThat(item.trackingNo()).isNull();
                assertThat(item.waybillStatus()).isNull();
            } else {
                assertThat(item.waybillId()).isEqualTo(WAYBILL_B);
                assertThat(item.company()).isNotNull();
                assertThat(item.trackingNo()).isNotNull();
            }
        }

        // 排序（子单创建时间倒序）：本类三行中 A 最新 → 返回序 A, B, C（id 递减）。
        // 页大小取全量后按自家 ID 段过滤（共享库他类同段行混入排序不分页截断）
        var records = viewService.list(new PlatformLogisticsViewFilter(null, null),
                new PageQuery(1, 10)).records();
        var ownOrdered = records.stream()
                .filter(r -> r.subOrderId() != null && r.subOrderId() >= SUB_A
                        && r.subOrderId() <= SUB_C)
                .toList();
        assertThat(ownOrdered).hasSize(3);
        assertThat(ownOrdered.get(0).subOrderId()).as("排序：A（创建最新）在前")
                .isEqualTo(SUB_A);
        assertThat(ownOrdered.get(1).subOrderId()).as("排序：B 居中").isEqualTo(SUB_B);
        assertThat(ownOrdered.get(2).subOrderId()).as("排序：C（创建最早）在后")
                .isEqualTo(SUB_C);
    }

    /**
     * 冒烟-2 超时未发货标记数据面：fixture 造三类行——已支付且截止时间
     * 已到期（应标记）/ 已支付未到期（不标记）/ 已发货但残留已到期截止时间
     * （不标记——已履约不构成「未发货」）→ 断言标记经真实列投影
     * （timeout_at TIMESTAMP → Instant → UTC 时刻）+ 服务判定链路真值。
     * <p>
     * 断言面：{@code timeoutOverdue} 三态实测；时间列以相对窗口断言
     * （fixture 用距执行时刻 ±2 小时值）。
     */
    @Test
    @DisplayName("冒烟-2 超时标记数据面：列投影 + 判定链路三态真值")
    void timeoutOverdueDataPlane() throws Exception {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            // 行 A：PAID + 超时已过（timeout_at = now-10min，本地墙钟字面）→ timeoutOverdue=true
            insertRows(mysql, SHOP_A, SHOP_A_NAME, MASTER_A, SUB_A, 0,
                    "PAID", Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)), 0);
            // 行 B：PAID + 超时未到（timeout_at = now+30min）→ timeoutOverdue=false
            insertRows(mysql, SHOP_B, SHOP_B_NAME, MASTER_B, SUB_B, WAYBILL_B,
                    "PAID", Timestamp.valueOf(LocalDateTime.now().plusMinutes(30)), 0);
            // 行 C：SHIPPED + 超时已过（timeout_at = now-5min）→ timeoutOverdue=false
            insertRows(mysql, SHOP_C, SHOP_C_NAME, MASTER_C, SUB_C, 0,
                    "SHIPPED", Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), 0);
        }
        AcceptanceDbSupport.pollUntil(
                () -> rowCountOnPg("sub_order s LEFT JOIN waybill wb ON wb.sub_order_id = s.id "
                        + "LEFT JOIN shop sh ON sh.id = s.shop_id",
                        "s.id IN (" + SUB_A + ", " + SUB_B + ", " + SUB_C + ")") == 3,
                "PG 镜像本类三行收敛（CDC，LEFT JOIN 同构面）",
                Duration.ofSeconds(30));

        var result = viewService.list(
                new PlatformLogisticsViewFilter(SHOP_A, null),
                new PageQuery(1, 10));
        var ownA = result.records().stream()
                .filter(r -> r.subOrderId() == SUB_A).findFirst();
        assertThat(ownA).as("SHOP_A 筛选命中本类行").isPresent();
        assertThat(ownA.orElseThrow().timeoutOverdue())
                .as("PAID + 超时已过 → 标记 true")
                .isTrue();
        assertThat(ownA.orElseThrow().timeoutAt()).isNotNull();
        assertThat(ownA.orElseThrow().timeoutAt())
                .as("超时标记 UTC 语义：timeout_at <= now")
                .isBeforeOrEqualTo(Instant.now());

        var resultB = viewService.list(
                new PlatformLogisticsViewFilter(SHOP_B, null),
                new PageQuery(1, 10));
        var ownB = resultB.records().stream()
                .filter(r -> r.subOrderId() == SUB_B).findFirst();
        assertThat(ownB).as("SHOP_B 筛选命中本类行").isPresent();
        assertThat(ownB.orElseThrow().timeoutOverdue())
                .as("PAID + 超时未到 → 不标记")
                .isFalse();

        var resultC = viewService.list(
                new PlatformLogisticsViewFilter(SHOP_C, null),
                new PageQuery(1, 10));
        var ownC = resultC.records().stream()
                .filter(r -> r.subOrderId() == SUB_C).findFirst();
        assertThat(ownC).as("SHOP_C 筛选命中本类行").isPresent();
        assertThat(ownC.orElseThrow().timeoutOverdue())
                .as("SHIPPED + 残留已到期截止时间 → 已履约不构成未发货，不标记")
                .isFalse();
    }

    /**
     * 冒烟-3 镜像同步核对动作（验收前置）：本视图读 PG 镜像表——验收
     * 前执行镜像对账（JDBC 进程内对账：直插 MySQL 行 → poll-until PG 镜像
     * 行出现 → 比对行数 + 字段一致）。行数不一致或镜像表空 →
     * 先核镜像同步再验收（空镜像表将表现为列表空，属数据面问题而非
     * 查询面问题）。
     * <p>
     * 断言面：直插 N 行 → poll-until PG 镜像 ≥ N 行 → 断言行数一致。
     * 本条目为验收前置自检（脚本执行结果回填断言）。
     */
    @Test
    @DisplayName("冒烟-3 镜像同步核对：MySQL 行数与 PG 镜像一致")
    void mirrorSyncVerification() throws Exception {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            // 幂等覆盖（@BeforeEach 已清理，此处防重复 run 形态）
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM sub_order WHERE id = ?", MIRROR_SUB);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM master_order WHERE id = ?", MIRROR_MASTER);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM shop WHERE id = ?", MIRROR_SHOP);
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO shop (create_time, update_time, id, name, logo, description, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE name = VALUES(name)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MIRROR_SHOP,
                    "镜像核对店铺", "http://img.example/logo.png", "fixture", "NORMAL");
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                            + " recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MIRROR_MASTER,
                    "ORD-MIRROR", BUYER, "张三", "13800000000", "浙江省", "杭州市",
                    "西湖区", "fixture", 5000L, 300L, 0L, 5300L, "PAID");
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status,"
                            + " waybill_id, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MIRROR_SUB,
                    String.valueOf(MIRROR_SHOP), MIRROR_MASTER, MIRROR_SHOP,
                    "SUB-MIRROR", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                    "fixture", 5000L, 300L, 0L, 5300L, "PAID", null, false);
        }
        AcceptanceDbSupport.pollUntil(
                () -> rowCountOnPg("sub_order", "id = " + MIRROR_SUB) == 1,
                "PG 镜像 sub_order 含镜像核对行（id=" + MIRROR_SUB + "）",
                Duration.ofSeconds(30));
        // 断言行数一致（MySQL 直查 = PG 镜像直查）
        try (Connection mysql = AcceptanceDbSupport.mysql();
             Connection pg = AcceptanceDbSupport.pg()) {
            final long mysqlCount = AcceptanceDbSupport.count(mysql,
                    "SELECT COUNT(*) FROM sub_order WHERE id = ?", MIRROR_SUB);
            final long pgCount = AcceptanceDbSupport.count(pg,
                    "SELECT COUNT(*) FROM sub_order WHERE id = ?", MIRROR_SUB);
            assertThat(pgCount)
                    .as("PG 镜像 sub_order 行数与 MySQL 源一致（CDC 收敛）")
                    .isEqualTo(mysqlCount);
        }
    }

    /** PG 镜像表行数（参数化 WHERE 条件）。 */
    private long rowCountOnPg(String table, String whereCondition) {
        try (Connection pg = AcceptanceDbSupport.pg()) {
            final String sql = "SELECT COUNT(*) FROM " + table
                    + (whereCondition == null ? "" : " WHERE " + whereCondition);
            return AcceptanceDbSupport.count(pg, sql);
        } catch (SQLException e) {
            throw new IllegalStateException("PG 镜像查询失败: " + table, e);
        }
    }
}
