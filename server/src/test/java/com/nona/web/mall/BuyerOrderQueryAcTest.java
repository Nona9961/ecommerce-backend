package com.nona.web.mall;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillTrackJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import com.nona.util.IDUtils;
import com.nona.util.JacksonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 买家订单查询装配面验收测试（装配面 1/2/3，真库面）：
 * mock 测不到的 PO 映射/Convertor 回填/归属过滤/分页排序逐一验证。
 * <ul>
 *     <li><b>装配面 1（createdAt 回填）</b>：真实仓储保存（DifferRepository
 *         → MasterOrderConvertor 主表落库 + 审计 create_time）→ 装载
 *         路径（safedConvertToRoot 回填 createdAt）→ GET /mall/orders/{id}
 *         createdAt 非空且与库列 create_time 字符串一致；新建路径 null
 *         不落库；</li>
 *     <li><b>装配面 2（查询真链 + PO 映射）</b>：listPaged/detail/waybill
 *         经真仓储 → 视图字段与 PO 往返一致；子单反查序/店铺名
 *         装配（真实 JOIN 装载）；分页排序（create_time DESC, id DESC）——
 *         跨买家 404 归属过滤真实生效；</li>
 *     <li><b>装配面 3（initiatePaymentWithView 字段透出）</b>：真主单 +
 *         真 PaymentUseCase/PaymentPort（支付单真实落库）+ mock 网关 →
 *         受理视图 8 字段全透出 + POST /mall/payments 线上 7 字段断言
 *         （cashierToken mock-cashier 前缀、paymentOrderId 与库列一致、
 *         复用语义）。</li>
 * </ul>
 * 装配策略：鉴权沿用 BuyerProductDetailApiIntegrationAcTest 先例
 * （真实 JWT + AuthUserCache 隔离 Redis）；造数全部经真实仓储
 * （save→Convertor→变更追踪落库），跨租户写（子单 tenant=shopId）经
 * {@link TenantPrivilege#elevated} 真实放行；常量 ID 与既有测试分域
 * （95xxx 店铺 / 85xxx 主单 / 75xxx 子单 / 65xxx 运单 / 45xxx 支付单）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class BuyerOrderQueryAcTest {

    /**
     * 买家 A 账号 ID（订单归属买家）
     */
    private static final long BUYER_A_UID = 55501L;

    /**
     * 买家 B 账号 ID（跨买家 404 断言面）
     */
    private static final long BUYER_B_UID = 55502L;

    /**
     * 店铺 A ID（子单 tenant 锚点）
     */
    private static final long SHOP_A_ID = 95501L;

    /**
     * 主单 A/B ID
     */
    private static final long MASTER_A_ID = 85501L;

    private static final long MASTER_B_ID = 85502L;

    /**
     * 子单 A ID（归属主单 A）
     */
    private static final long SUB_A_ID = 75501L;

    /**
     * 子单 B ID（归属主单 B）
     */
    private static final long SUB_B_ID = 75502L;

    /**
     * 支付单 ID（详情 payment 嵌套造数）
     */
    private static final long PAYMENT_A_ID = 45501L;

    /**
     * 运单 ID（waybill 装配面造数）
     */
    private static final long WAYBILL_A_ID = 65501L;

    /**
     * 地址快照基线（六段必填）
     */
    private static final AddressSnapshot ADDRESS = new AddressSnapshot(
            "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");

    /**
     * 金额基线（商品 5000 + 运费 300 = 实付 5300 分）
     */
    private static final AmountDetail AMOUNT = new AmountDetail(5000L, 300L, 0L, 5300L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TenantPrivilege tenantPrivilege;

    @Autowired
    private MasterOrderRepository masterOrderRepository;

    @Autowired
    private SubOrderRepository subOrderRepository;

    @Autowired
    private WaybillRepository waybillRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private MasterOrderJpaRepository masterOrderJpa;

    @Autowired
    private SubOrderJpaRepository subOrderJpa;

    @Autowired
    private OrderItemJpaRepository orderItemJpa;

    @Autowired
    private PaymentOrderJpaRepository paymentOrderJpa;

    @Autowired
    private WaybillJpaRepository waybillJpa;

    @Autowired
    private WaybillTrackJpaRepository waybillTrackJpa;

    @Autowired
    private ShopJpaRepository shopJpa;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 支付渠道（mock，仅受理面隔离——编排/端口/仓储全真链）
     */
    @MockitoBean
    private PaymentGateway paymentGateway;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 每用例前清表（依赖序：从表/租户表在前）+ stub 缓存（先例同构）。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            orderItemJpa.deleteAll();
            subOrderJpa.deleteAll();
        });
        waybillTrackJpa.deleteAll();
        waybillJpa.deleteAll();
        paymentOrderJpa.deleteAll();
        masterOrderJpa.deleteAll();
        shopJpa.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(BUYER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
        when(authUserCache.get(BUYER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
    }

    /* ------------------------------------------------------------------ */
    /* 造数辅助（真实仓储 save→Convertor→变更追踪落库）                    */
    /* ------------------------------------------------------------------ */

    /**
     * 店铺落库（global 表，直存真实仓储——变更追踪需要请求跟踪作用域，
     * withScope 先例 CartRepositoryIntegrationAcTest 同构）。
     */
    private void shopRow() {
        final Shop shop = new Shop(SHOP_A_ID, "店铺甲", "http://img.example/logo.png",
                "测试店铺", ShopStatus.NORMAL);
        TrackingContext.withScope(() -> shopRepository.save(shop));
    }

    /**
     * 主单落库（8 参带状态构造 + 真实仓储——createdAt 为 null 的新建
     * 形态，落库后编排回填）。
     *
     * @param id     主单 ID
     * @param status 落库状态
     * @return 主单实体（保存后）
     */
    private MasterOrder masterRow(long id, long buyerId, MasterOrderStatus status) {
        final MasterOrder master = new MasterOrder(id, "ORD" + id, buyerId,
                ADDRESS, AMOUNT, List.of(id + 1000L), null, status);
        TrackingContext.withScope(() -> masterOrderRepository.save(master));
        return master;
    }

    /**
     * 子单落库（tenant=shopId 跨租户写：PO 直插 + 显式 tenantID + 提权
     * 真实放行——BuyerProductDetail 先例同构；领域仓储存子单需 Hibernate
     * 租户注入面装配，非本装配面目标）。订单项从表 JSON 列以项目统一
     * Jackson 序列化形态直插。
     *
     * @param id        子单 ID
     * @param masterId  归属主单 ID
     * @param status    履约状态
     * @param waybillId 关联运单 ID（可空）
     */
    private void subRow(long id, long masterId, SubOrderStatus status, Long waybillId) {
        final SubOrderPO po = new SubOrderPO();
        po.setId(id);
        po.setTenantID(String.valueOf(SHOP_A_ID));
        po.setMasterOrderId(masterId);
        po.setShopId(SHOP_A_ID);
        po.setSubOrderNo("SUB" + id);
        po.setRecipient("张三");
        po.setPhone("13800000000");
        po.setProvince("浙江省");
        po.setCity("杭州市");
        po.setDistrict("西湖区");
        po.setDetail("文一西路 1 号");
        po.setGoodsAmount(5000L);
        po.setFreightAmount(300L);
        po.setDiscount(0L);
        po.setPaidAmount(5300L);
        po.setStatus(status);
        po.setWaybillId(waybillId);
        tenantPrivilege.elevated(() -> {
            subOrderJpa.save(po);
        });
        final OrderItemPO itemPo = new OrderItemPO();
        itemPo.setId(IDUtils.generateID());
        itemPo.setTenantID(String.valueOf(SHOP_A_ID));
        itemPo.setSubOrderId(id);
        itemPo.setProductId(900L);
        itemPo.setSkuId(800L);
        itemPo.setProductName("测试商品");
        itemPo.setUnitPrice(5000L);
        itemPo.setQuantity(1);
        itemPo.setSubtotal(5000L);
        itemPo.setMainImageUrl("http://img.example/cover.jpg");
        itemPo.setSpecSummary("颜色:黑,尺码:M");
        itemPo.setSpecAttributes(JacksonUtil.toJsonString(Map.of("颜色", "黑")));
        itemPo.setCustomAttributes(JacksonUtil.toJsonString(Map.of("产地", "中国")));
        tenantPrivilege.elevated(() -> {
            orderItemJpa.save(itemPo);
        });
    }

    /**
     * 支付单落库（PENDING_PAYMENT 装载形态，关联主单一对一）。
     */
    private void paymentRow(long id, long masterId) {
        final PaymentOrder payment = new PaymentOrder(id, "PAY" + id, masterId, 5300L,
                "MOCK", Instant.now().plusSeconds(1800), PaymentOrderStatus.PENDING_PAYMENT,
                null, List.of());
        TrackingContext.withScope(() -> paymentOrderRepository.save(payment));
    }

    /**
     * 运单落库（global 表 + 轨迹从表随仓储 save 落库）。
     */
    private void waybillRow(long id, long subId) {
        final WaybillTrack track = new WaybillTrack(IDUtils.generateID(), id,
                WaybillStatus.SHIPPED, LocalDateTime.now().minusMinutes(10), "包裹已揽收");
        final Waybill waybill = new Waybill(id, subId, "测试物流", "SF000000001",
                WaybillStatus.SHIPPED, List.of(track));
        TrackingContext.withScope(() -> waybillRepository.save(waybill));
    }

    /**
     * 买家 A Bearer 令牌（真实 JWT 签发）。
     *
     * @return Authorization 头值
     */
    private String bearerA() {
        return "Bearer " + tokenProvider.issueToken(BUYER_A_UID, Portal.MALL);
    }

    /**
     * 买家 B Bearer 令牌（真实 JWT 签发）。
     *
     * @return Authorization 头值
     */
    private String bearerB() {
        return "Bearer " + tokenProvider.issueToken(BUYER_B_UID, Portal.MALL);
    }

    /* ------------------------------------------------------------------ */
    /* 装配面 1：createdAt 回填链                                          */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("装配面-1 createdAt 回填：真实落库/装载链 → 详情 createdAt 非空且与库列 create_time 一致 + payment 嵌套 + 跨买家 404")
    void detail_createdAtRoundTrip_andPaymentNested_andCrossBuyer404() throws Exception {
        shopRow();
        masterRow(MASTER_A_ID, BUYER_A_UID, MasterOrderStatus.PENDING_PAYMENT);
        subRow(SUB_A_ID, MASTER_A_ID, SubOrderStatus.PENDING_PAYMENT, null);
        paymentRow(PAYMENT_A_ID, MASTER_A_ID);

        // 详情真链（真实仓储装载 → Convertor 回填 createdAt）
        final MvcResult result = mockMvc.perform(get("/mall/orders/" + MASTER_A_ID)
                        .header("Authorization", bearerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterOrderId").value(MASTER_A_ID))
                .andExpect(jsonPath("$.data.orderNo").value("ORD" + MASTER_A_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.address.receiverName").value("张三"))
                .andExpect(jsonPath("$.data.goodsAmount").value(5000))
                .andExpect(jsonPath("$.data.paidAmount").value(5300))
                .andExpect(jsonPath("$.data.subOrders[0].subOrderNo").value("SUB" + SUB_A_ID))
                .andExpect(jsonPath("$.data.subOrders[0].shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.subOrders[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.subOrders[0].items[0].specAttributes.颜色").value("黑"))
                .andExpect(jsonPath("$.data.payment.paymentOrderId").value(PAYMENT_A_ID))
                .andExpect(jsonPath("$.data.payment.payNo").value("PAY" + PAYMENT_A_ID))
                .andExpect(jsonPath("$.data.payment.amount").value(5300))
                .andExpect(jsonPath("$.data.payment.timeoutAt").isNotEmpty())
                .andExpect(jsonPath("$.data.payment.status").value("PENDING_PAYMENT"))
                .andReturn();

        // createdAt 与库列一致（真实 DB 直查 + 字符串比较）
        final String createdAt = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("createdAt").asText();
        final String dbCreateTime = masterOrderJpa.findById(MASTER_A_ID)
                .orElseThrow().getCreateTime().toString();
        org.assertj.core.api.Assertions.assertThat(createdAt).isEqualTo(dbCreateTime);

        // 跨买家 404（归属过滤真实生效）
        mockMvc.perform(get("/mall/orders/" + MASTER_A_ID)
                        .header("Authorization", bearerB()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order.master_not_found"));
    }

    /* ------------------------------------------------------------------ */
    /* 装配面 2：查询真链 + PO 映射 + 分页排序                             */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("装配面-2 列表真链：状态 tab 过滤 + create_time DESC/id DESC 排序 + 数量 COUNT 回显 + 子单/店铺名装配")
    void listOrders_realChain_filterAndOrdering() throws Exception {
        shopRow();
        masterRow(MASTER_A_ID, BUYER_A_UID, MasterOrderStatus.PAID);
        subRow(SUB_A_ID, MASTER_A_ID, SubOrderStatus.PAID, null);
        masterRow(MASTER_B_ID, BUYER_A_UID, MasterOrderStatus.PENDING_PAYMENT);
        subRow(SUB_B_ID, MASTER_B_ID, SubOrderStatus.PENDING_PAYMENT, null);

        // PAID tab 过滤：仅主单 A（后造主单 B 被排除）
        mockMvc.perform(get("/mall/orders")
                        .header("Authorization", bearerA())
                        .param("status", "PAID")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].masterOrderId").value(MASTER_A_ID))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].shopName").value("店铺甲"));

        // 全部 tab：2 条，倒序（后造主单 B 在前——create_time DESC, id DESC）
        mockMvc.perform(get("/mall/orders")
                        .header("Authorization", bearerA())
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].masterOrderId").value(MASTER_B_ID))
                .andExpect(jsonPath("$.data.records[1].masterOrderId").value(MASTER_A_ID));

        // 分页：pageSize=1 → 第 1 页 1 条 + pageNum/pageSize 回显 + 排序保持
        mockMvc.perform(get("/mall/orders")
                        .header("Authorization", bearerA())
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.records[0].masterOrderId").value(MASTER_B_ID));
    }

    @Test
    @DisplayName("装配面-2 waybill 真链：真实仓储运单/轨迹/子单/店铺装配 + 无运单 404 + 跨买家 404")
    void waybill_realChain_assemblesAndRejects() throws Exception {
        shopRow();
        masterRow(MASTER_A_ID, BUYER_A_UID, MasterOrderStatus.SHIPPED);
        subRow(SUB_A_ID, MASTER_A_ID, SubOrderStatus.SHIPPED, WAYBILL_A_ID);
        waybillRow(WAYBILL_A_ID, SUB_A_ID);

        // 运单真链：公司/运单号/状态/轨迹/子单号/店铺名/商品行
        mockMvc.perform(get("/mall/sub-orders/" + SUB_A_ID + "/waybill")
                        .header("Authorization", bearerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillId").value(WAYBILL_A_ID))
                .andExpect(jsonPath("$.data.subOrderId").value(SUB_A_ID))
                .andExpect(jsonPath("$.data.company").value("测试物流"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF000000001"))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracks[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracks[0].description").value("包裹已揽收"))
                .andExpect(jsonPath("$.data.subOrderNo").value("SUB" + SUB_A_ID))
                .andExpect(jsonPath("$.data.shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.items[0].skuId").value(800))
                .andExpect(jsonPath("$.data.items[0].specSummary").value("颜色:黑,尺码:M"));

        // 跨买家 404（归属过滤 order.sub_not_found）
        mockMvc.perform(get("/mall/sub-orders/" + SUB_A_ID + "/waybill")
                        .header("Authorization", bearerB()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order.sub_not_found"));

        // 无运单子单 → logistics.not_found（子单 B 归属买家 B、无运单）
        masterRow(MASTER_B_ID, BUYER_B_UID, MasterOrderStatus.PAID);
        subRow(SUB_B_ID, MASTER_B_ID, SubOrderStatus.PAID, null);
        mockMvc.perform(get("/mall/sub-orders/" + SUB_B_ID + "/waybill")
                        .header("Authorization", bearerB()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("logistics.not_found"));
    }

    /* ------------------------------------------------------------------ */
    /* 装配面 3：initiatePaymentWithView 字段透出（真链 + mock 网关）      */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("装配面-3 受理视图：真主单 + 真端口（支付单落库）+ mock 网关 → 8 字段透出 + 端点 7 字段 + 复用")
    void initiatePayment_viewTransparent() throws Exception {
        shopRow();
        masterRow(MASTER_A_ID, BUYER_A_UID, MasterOrderStatus.PENDING_PAYMENT);
        subRow(SUB_A_ID, MASTER_A_ID, SubOrderStatus.PENDING_PAYMENT, null);
        when(paymentGateway.acquire(any(AcquireRequest.class)))
                .thenReturn(new AcquireResult(true, "PAY" + MASTER_A_ID,
                        "CHANNEL-TXN-999", "mock-cashier:demo"));

        // 端点真链：POST /mall/payments → 真用例/真端口（支付单落库）+ mock 网关 → 7 字段
        final MvcResult result = mockMvc.perform(post("/mall/payments")
                        .header("Authorization", bearerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterOrderId\":" + MASTER_A_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.paymentOrderId").isNumber())
                .andExpect(jsonPath("$.data.payNo").isNotEmpty())
                .andExpect(jsonPath("$.data.amount").value(5300))
                .andExpect(jsonPath("$.data.timeoutAt").isNotEmpty())
                .andExpect(jsonPath("$.data.channelTxnNo").value("CHANNEL-TXN-999"))
                .andExpect(jsonPath("$.data.cashierToken").value("mock-cashier:demo"))
                .andReturn();

        // 受理视图字段与库列一致（支付单真实落库：order_id 唯一一对一）
        final long paymentOrderId = objectMapper.readTree(
                        result.getResponse().getContentAsString())
                .path("data").path("paymentOrderId").asLong();
        org.assertj.core.api.Assertions.assertThat(
                paymentOrderJpa.findById(paymentOrderId).orElseThrow()).isNotNull();

        // 二次发起 → 复用同一支付单（PENDING_PAYMENT 复用语义，不新建）
        mockMvc.perform(post("/mall/payments")
                        .header("Authorization", bearerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterOrderId\":" + MASTER_A_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentOrderId").value(paymentOrderId));
    }
}