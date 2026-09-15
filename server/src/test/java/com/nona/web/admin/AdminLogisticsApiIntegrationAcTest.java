package com.nona.web.admin;

import com.nona.api.auth.Portal;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台物流总览端点装配面测试（装配清单 #7：GET /admin/logistics
 * 复用已冻结的 PlatformLogisticsViewService 链路——真实 Security
 * 链 + JWT + replica 镜像表数据，PG 镜像三表直插跨店行集）。
 * <p>
 * 覆盖：跨店铺全集行形状（12 字段逐字段，枚举 name()/Instant ISO
 * 字符串承载）；shopId/status 过滤；timeoutOverdue 行级判定真值
 * （PAID 已到期标 / 已发货残留 deadline 不标）；未登录取数 401。
 * <p>
 * 镜像表数据面说明：waybill/sub_order/shop 三表为 CDC 镜像（PG 侧
 * 结构由外部迁移塑造）——本测试以 fixture 直插镜像行验证查询链路
 * 与判定语义（镜像同步链路本身的行数核对属验收前人工动作，见
 * PlatformLogisticsViewAcTest 冒烟-3）。运行渠道 = test profile +
 * 本地隧道（PG），-Pfull -Dtest 显式执行。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class AdminLogisticsApiIntegrationAcTest {

    /**
     * 平台运营账号 ID
     */
    private static final long ADMIN_UID = 99001L;

    /**
     * 两店铺 ID（跨店全集面）
     */
    private static final long SHOP_A_ID = 98101L;
    private static final long SHOP_B_ID = 98102L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * replica 数据源（PG 镜像表直插）
     */
    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * JWT 签发器（构造合法平台 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，注入 ADMIN 角色）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前：清空镜像三表并直插跨店行集。
     */
    @BeforeEach
    void setUp() {
        replicaJdbcTemplate.update("DELETE FROM sub_order");
        replicaJdbcTemplate.update("DELETE FROM waybill");
        replicaJdbcTemplate.update("DELETE FROM shop");

        // 店铺镜像（店名投影面；CDC 镜像结构——时间列 NOT NULL 必填）
        replicaJdbcTemplate.update(
                "INSERT INTO shop (id, name, create_time, update_time, status) VALUES (?, ?, now(), now(), ?)",
                SHOP_A_ID, "店铺A", "NORMAL");
        replicaJdbcTemplate.update(
                "INSERT INTO shop (id, name, create_time, update_time, status) VALUES (?, ?, now(), now(), ?)",
                SHOP_B_ID, "店铺B", "NORMAL");

        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    /**
     * 装配 #7 happy：跨店全集行形状（12 字段逐字段 + timeoutOverdue 真值
     * 三态：PAID 已到期标、PAID 未到期不标、SHIPPED 残留 deadline 不标）。
     */
    @Test
    @DisplayName("物流总览：跨店全集行形状 + 超时标记三态真值")
    void listLogistics_fullShapeAndOverdueTruth() throws Exception {
        final Instant now = Instant.now();
        // 行 1：A 店 PAID + 已到期 deadline → overdue true；已发货运单附注
        // （create_time 互异保证固定排序断言：最新下单在前）
        insertSubOrderRow(1L, "SO1", SubOrderStatus.PAID, SHOP_A_ID,
                Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.minusSeconds(7200)));
        // 行 2：B 店 PAID + 未到期 deadline → overdue false
        insertSubOrderRow(2L, "SO2", SubOrderStatus.PAID, SHOP_B_ID,
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.minusSeconds(5400)));
        // 行 3：A 店 SHIPPED + 残留已到期 deadline → overdue false（已履约）
        insertSubOrderRow(3L, "SO3", SubOrderStatus.SHIPPED, SHOP_A_ID,
                Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.minusSeconds(3600)));
        // 行 4：A 店未发货（PAID 无 deadline、无运单）→ overdue false 且物流附注空
        insertSubOrderRow(4L, "SO4", SubOrderStatus.PAID, SHOP_A_ID,
                null, Timestamp.from(now.minusSeconds(1800)));
        insertWaybillRow(1L, 1L);

        mockMvc.perform(get("/admin/logistics").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                // records[0]（最新下单）= 行 4：PAID 无 deadline 未发货，附注空
                .andExpect(jsonPath("$.data.records[0].subOrderId").value(4))
                .andExpect(jsonPath("$.data.records[0].shopName").value("店铺A"))
                .andExpect(jsonPath("$.data.records[0].subOrderStatus").value("PAID"))
                .andExpect(jsonPath("$.data.records[0].timeoutAt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].waybillId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].timeoutOverdue").value(false))
                // records[1] = 行 3：SHIPPED 残留 deadline 不标记
                .andExpect(jsonPath("$.data.records[1].subOrderId").value(3))
                .andExpect(jsonPath("$.data.records[1].timeoutOverdue").value(false))
                // records[2] = 行 2：PAID 未到期不标记
                .andExpect(jsonPath("$.data.records[2].subOrderId").value(2))
                .andExpect(jsonPath("$.data.records[2].timeoutOverdue").value(false))
                // records[3] = 行 1：PAID 已到期标记 + 运单附注 + 12 字段形状
                .andExpect(jsonPath("$.data.records[3].subOrderId").value(1))
                .andExpect(jsonPath("$.data.records[3].subOrderNo").value("SO1"))
                .andExpect(jsonPath("$.data.records[3].masterOrderId").value(100))
                .andExpect(jsonPath("$.data.records[3].shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.records[3].shopName").value("店铺A"))
                .andExpect(jsonPath("$.data.records[3].subOrderStatus").value("PAID"))
                .andExpect(jsonPath("$.data.records[3].waybillId").value(1))
                .andExpect(jsonPath("$.data.records[3].company").value("顺丰速运"))
                .andExpect(jsonPath("$.data.records[3].trackingNo").value("SF001"))
                .andExpect(jsonPath("$.data.records[3].waybillStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.records[3].timeoutAt").isString())
                .andExpect(jsonPath("$.data.records[3].timeoutOverdue").value(true));
    }

    /**
     * 装配 #7：shopId/status 过滤生效 + 非法 status 400。
     */
    @Test
    @DisplayName("物流总览：shopId/status 过滤 + 非法值拒绝")
    void listLogistics_filterAndInvalidRejected() throws Exception {
        final Instant now = Instant.now();
        insertSubOrderRow(1L, "SO1", SubOrderStatus.PAID, SHOP_A_ID, null,
                Timestamp.from(now.minusSeconds(7200)));
        insertSubOrderRow(2L, "SO2", SubOrderStatus.SHIPPED, SHOP_B_ID, null,
                Timestamp.from(now.minusSeconds(3600)));

        // shopId 过滤：仅 A 店
        mockMvc.perform(get("/admin/logistics?shopId=" + SHOP_A_ID)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].shopId").value(SHOP_A_ID));
        // status 过滤：仅 SHIPPED
        mockMvc.perform(get("/admin/logistics?status=SHIPPED")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].subOrderStatus").value("SHIPPED"));
        // 非法 status → 400
        mockMvc.perform(get("/admin/logistics?status=NOT_A_STATUS")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        // 未登录取数 → 401
        mockMvc.perform(get("/admin/logistics")).andExpect(status().isUnauthorized());
    }

    /* ================= fixture helpers ================= */

    /**
     * 直插子单镜像行。
     *
     * @param id        子单 ID
     * @param no        子单号
     * @param status    履约状态（枚举名文字存储）
     * @param shopId    归属店铺 ID
     * @param timeoutAt 发货超时截止（可空）
     * @param createdAt 创建时间（固定排序列）
     */
    private void insertSubOrderRow(long id, String no, SubOrderStatus status, long shopId,
                                   Timestamp timeoutAt, Timestamp createdAt) {
        // 镜像表 NOT NULL 列集全量直插（CDC 镜像结构：时间列/租户列/地址快照
        // 列/金额列/claimed 均不可空）；timeout_at 随 fixture 语义（超时三态）
        replicaJdbcTemplate.update(
                "INSERT INTO sub_order (id, create_time, update_time, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, timeout_at, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, createdAt, createdAt, String.valueOf(shopId), 100L, shopId, no,
                "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                48000L, 800L, 0L, 48800L, status.name(), timeoutAt, false);
    }

    /**
     * 直插运单镜像行（物流凭据附注面）。
     *
     * @param id          运单 ID
     * @param subOrderId  归属子单 ID
     */
    private void insertWaybillRow(long id, long subOrderId) {
        // 镜像表 NOT NULL 时间列必填
        replicaJdbcTemplate.update(
                "INSERT INTO waybill (id, create_time, update_time, sub_order_id, company,"
                        + " tracking_no, status) VALUES (?, now(), now(), ?, ?, ?, ?)",
                id, subOrderId, "顺丰速运", "SF001", "IN_TRANSIT");
    }

    /**
     * 构造平台 token（uid + ADMIN portal；角色经 mock 用户上下文提供）。
     *
     * @return Bearer token
     */
    private String bearer() {
        return "Bearer " + tokenProvider.issueToken(ADMIN_UID, Portal.ADMIN);
    }
}