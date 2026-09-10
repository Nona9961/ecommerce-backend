package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillTrackJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家/平台侧接线装配面测试（WU-60 装配清单 #1/#2/#3/#4/#5/#6，真实
 * 装配：Security 链 + JWT + MySQL 真库 + 租户上下文）。
 * <p>
 * 覆盖：
 * <ul>
 *     <li>#1 端点形状：9 seller 端点逐字段断言 + 未登录取数 401 + B 店铺
 *         token 请求 A 店铺子单详情/发货/库存流水按 404 呈现（S2.3/G1.1
 *         锚点端点面，fail-closed 归属不泄露）；</li>
 *     <li>#2 列表 status 逗号分隔多值绑定生效、缺省全量；</li>
 *     <li>#3 分页参数归一化端点面（pageNum=0→1、pageSize=200→100）；</li>
 *     <li>#4 SubOrder PO create_time 装载实链（真库直插 → 详情/列表
 *         createTime 非空 ISO 字符串——convertor 回填实证，mock 测不到）；</li>
 *     <li>#5 运单悬挂真实形态（真库装配：已发货非空/null、未发货 null、
 *         引用悬挂 null 容忍呈现）；</li>
 *     <li>#6 catalog join 投影装配（库存行 + 投影接口真库：join 命中
 *         形状；SKU 移除后 join 缺失按数据异常呈现 fail-closed）。</li>
 * </ul>
 * 测试数据自清理（@BeforeEach 清本测试用表后直插）；运行渠道 =
 * test profile + MySQL 真库（ecommerce_test，宿主隧道 + -Pfull -Dtest
 * 显式执行，同既有 AcTest 纪律）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class SellerAdminWireAcTest {

    /**
     * 商家 A / B 账号 ID
     */
    private static final long SELLER_A_UID = 88001L;
    private static final long SELLER_B_UID = 88002L;

    /**
     * 店铺 A / B ID（租户锚点）
     */
    private static final long SHOP_A_ID = 98001L;
    private static final long SHOP_B_ID = 98002L;

    /**
     * 固定业务行 ID（Snowflake 形状占位，测试库内唯一）
     */
    private static final long SUB_ORDER_ID = 76001L;
    private static final long MASTER_ID = 75001L;
    private static final long WAYBILL_ID = 74001L;
    private static final long SKU_ID = 70001L;
    private static final long PRODUCT_ID = 72001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 店铺主表 JPA（店铺造数/清理）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    @Autowired
    private MasterOrderJpaRepository masterOrderJpaRepository;

    /**
     * 商品主表 JPA（清理——SKU 集经 API 配置后直插库存行）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 商品 SKU 子表 JPA（清理与 join 缺失删行）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

    /**
     * 子单主表 JPA（直插子单行）
     */
    @Autowired
    private SubOrderJpaRepository subOrderJpaRepository;

    /**
     * 订单项从表 JPA（直插快照行）
     */
    @Autowired
    private OrderItemJpaRepository orderItemJpaRepository;

    /**
     * 运单主表 JPA（直插运单行）
     */
    @Autowired
    private WaybillJpaRepository waybillJpaRepository;

    /**
     * 运单轨迹从表 JPA（直插轨迹行）
     */
    @Autowired
    private WaybillTrackJpaRepository waybillTrackJpaRepository;

    /**
     * 库存主表 JPA（直插库存行）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 库存流水表 JPA（直插流水行）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * JWT 签发器（构造合法商家 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis 并注入店铺归属）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 提权工具（跨租户清理/直插 tenant-scoped 表）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：清空相关表并直插 A/B 两家店铺，stub 两个商家上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
            orderItemJpaRepository.deleteAll();
            subOrderJpaRepository.deleteAll();
            waybillTrackJpaRepository.deleteAll();
            waybillJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
            masterOrderJpaRepository.deleteAll();
        });
        shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
        shopJpaRepository.save(shopPo(SHOP_B_ID, "店铺B"));
        // 子单挂载的主单行（发货编排/订单查询的 master 装载锚点——自足造数）
        masterOrderJpaRepository.save(masterPo());
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
    }

    /* ================= #1 端点形状 ================= */

    /**
     * 装配 #1：列表端点形状（行 11 字段逐字段，金额分）+ #2 status 逗号
     * 分隔多值过滤 + 缺省全量 + #3 分页归一化端点面。
     */
    @Test
    @DisplayName("订单列表：行形状金额分 + status 逗号分隔过滤 + 缺省全量 + 分页归一化")
    void orderList_wireShapeAndFilterAndPaging() throws Exception {
        insertSubOrder(SUB_ORDER_ID, SubOrderStatus.REFUNDING, null);
        insertSubOrder(SUB_ORDER_ID + 1, SubOrderStatus.SHIPPED, WAYBILL_ID);
        insertWaybill(WAYBILL_ID, SUB_ORDER_ID + 1);

        // 缺省全量：两行都在
        mockMvc.perform(get("/seller/orders").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].subOrderId").isNumber())
                .andExpect(jsonPath("$.data.records[0].subOrderNo").isString())
                .andExpect(jsonPath("$.data.records[0].masterOrderId").value(MASTER_ID))
                .andExpect(jsonPath("$.data.records[0].status").isString())
                .andExpect(jsonPath("$.data.records[0].recipient").value("张三"))
                .andExpect(jsonPath("$.data.records[0].goodsAmount").value(48000))
                .andExpect(jsonPath("$.data.records[0].freightAmount").value(800))
                .andExpect(jsonPath("$.data.records[0].paidAmount").value(48800))
                .andExpect(jsonPath("$.data.records[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.records[0].firstImageUrl").value("/files/p3001.png"))
                .andExpect(jsonPath("$.data.records[0].createTime").isString());

        // status 逗号分隔多值：仅 REFUNDING 命中
        mockMvc.perform(get("/seller/orders?status=REFUNDING,REFUNDED")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("REFUNDING"));

        // 非法状态值显式解析拒绝 400
        mockMvc.perform(get("/seller/orders?status=NOT_A_STATUS")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isBadRequest());

        // 分页归一化：pageNum=0 → 1、pageSize=200 → 100
        mockMvc.perform(get("/seller/orders?pageNum=0&pageSize=200")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100));
    }

    /**
     * 装配 #1/#4/#5：详情端点形状（10 字段 + 地址 6/金额 4/订单项 8/运单
     * 3 逐字段；createTime 为装载回填审计时间 ISO 字符串）。
     */
    @Test
    @DisplayName("订单详情：已发货全形状 + createTime 装载回填非空 ISO")
    void orderDetail_shippedFullShape() throws Exception {
        insertSubOrder(SUB_ORDER_ID, SubOrderStatus.SHIPPED, WAYBILL_ID);
        insertWaybill(WAYBILL_ID, SUB_ORDER_ID);

        mockMvc.perform(get("/seller/orders/" + SUB_ORDER_ID)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subOrderId").value(SUB_ORDER_ID))
                .andExpect(jsonPath("$.data.subOrderNo").value("SO" + SUB_ORDER_ID))
                .andExpect(jsonPath("$.data.masterOrderId").value(MASTER_ID))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.address.recipient").value("张三"))
                .andExpect(jsonPath("$.data.address.phone").value("13800000000"))
                .andExpect(jsonPath("$.data.address.province").value("浙江省"))
                .andExpect(jsonPath("$.data.address.city").value("杭州市"))
                .andExpect(jsonPath("$.data.address.district").value("西湖区"))
                .andExpect(jsonPath("$.data.address.detail").value("文一西路 1 号"))
                .andExpect(jsonPath("$.data.amount.goodsAmount").value(48000))
                .andExpect(jsonPath("$.data.amount.freightAmount").value(800))
                .andExpect(jsonPath("$.data.amount.discount").value(0))
                .andExpect(jsonPath("$.data.amount.paidAmount").value(48800))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productId").value(PRODUCT_ID))
                .andExpect(jsonPath("$.data.items[0].skuId").value(SKU_ID))
                .andExpect(jsonPath("$.data.items[0].productName").value("测试商品"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(15000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(30000))
                .andExpect(jsonPath("$.data.items[0].mainImageUrl").value("/files/p3001.png"))
                .andExpect(jsonPath("$.data.items[0].specSummary").value("颜色:黑,尺码:M"))
                .andExpect(jsonPath("$.data.waybill.company").value("顺丰速运"))
                .andExpect(jsonPath("$.data.waybill.trackingNo").value("SF202609080001"))
                .andExpect(jsonPath("$.data.waybill.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.createTime").isString());
    }

    /**
     * 装配 #5：运单悬挂真实形态——未发货 waybill null、已发货引用悬挂
     * （waybill 行缺失 = 数据异常）按 null 容忍呈现。
     */
    @Test
    @DisplayName("订单详情：未发货与运单引用悬挂 waybill 均 null 容忍")
    void orderDetail_waybillNullAndHangingTolerance() throws Exception {
        // 未发货：waybillId 引用空
        insertSubOrder(SUB_ORDER_ID, SubOrderStatus.PAID, null);
        mockMvc.perform(get("/seller/orders/" + SUB_ORDER_ID)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.waybill").doesNotExist());

        // 引用悬挂：waybillId 指向不存在的运单行（未直插运单）
        insertSubOrder(SUB_ORDER_ID + 1, SubOrderStatus.SHIPPED, WAYBILL_ID + 9999);
        mockMvc.perform(get("/seller/orders/" + (SUB_ORDER_ID + 1))
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.waybill").doesNotExist());
    }

    /**
     * 装配 #1：发货端点接线（幂等 + 运单创建 + 状态推进）与请求校验
     * （必填非空 400）。
     */
    @Test
    @DisplayName("发货端点点线：发货推进 + 幂等短路 + 空白拒绝")
    void ship_wireIdempotentAndValidation() throws Exception {
        insertSubOrder(SUB_ORDER_ID, SubOrderStatus.PAID, null);

        mockMvc.perform(post("/seller/sub-orders/" + SUB_ORDER_ID + "/ship")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"顺丰速运\",\"trackingNo\":\"SF202609080001\"}"))
                .andExpect(status().isOk());

        // 状态已推进 SHIPPED 且运单概要非空
        mockMvc.perform(get("/seller/orders/" + SUB_ORDER_ID)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.waybill.company").value("顺丰速运"));

        // 幂等：重复发货返回成功
        mockMvc.perform(post("/seller/sub-orders/" + SUB_ORDER_ID + "/ship")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"顺丰速运\",\"trackingNo\":\"SF202609080001\"}"))
                .andExpect(status().isOk());

        // 请求校验：公司名空白 → 400
        mockMvc.perform(post("/seller/sub-orders/" + SUB_ORDER_ID + "/ship")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"\",\"trackingNo\":\"SF202609080001\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 装配 #1/#6：库存三端点（列表 join 形状 / 调整响应回显 / 流水分页
     * 14 字段）+ join 缺失 fail-closed + 调整致负拒绝。
     */
    @Test
    @DisplayName("库存端点点线：列表 join + 调整回显 + 流水形状 + join 缺失 fail-closed")
    void inventory_wireListAdjustLogsJoinMissing() throws Exception {
        insertInventoryRow(SKU_ID, 15, 5, 30);
        insertInventoryLogRow(60001L, SKU_ID);

        // 列表：行 7 字段 + join 商品摘要命中
        mockMvc.perform(get("/seller/inventory").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].skuId").value(SKU_ID))
                .andExpect(jsonPath("$.data.records[0].productId").value(PRODUCT_ID))
                .andExpect(jsonPath("$.data.records[0].productName").value("经典款 T 恤"))
                .andExpect(jsonPath("$.data.records[0].specSummary").value("颜色:黑,尺码:M"))
                .andExpect(jsonPath("$.data.records[0].available").value(15))
                .andExpect(jsonPath("$.data.records[0].held").value(5))
                .andExpect(jsonPath("$.data.records[0].sold").value(30));

        // 调整：delta +5 → 响应行三态更新
        mockMvc.perform(put("/seller/inventory/" + SKU_ID)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"补货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(SKU_ID))
                .andExpect(jsonPath("$.data.available").value(20))
                .andExpect(jsonPath("$.data.held").value(5))
                .andExpect(jsonPath("$.data.sold").value(30));

        // 调整致负 → 400 inventory.insufficient
        mockMvc.perform(put("/seller/inventory/" + SKU_ID)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("inventory.insufficient"));

        // 流水：14 字段形状（append-only 新流水在前——先手动调整行再直插行）
        mockMvc.perform(get("/seller/inventory/" + SKU_ID + "/logs")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").isNumber())
                .andExpect(jsonPath("$.data.records[0].skuId").value(SKU_ID))
                .andExpect(jsonPath("$.data.records[0].type").value("MANUAL_ADJUST"))
                .andExpect(jsonPath("$.data.records[0].delta").value(5))
                .andExpect(jsonPath("$.data.records[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].beforeAvailable").value(15))
                .andExpect(jsonPath("$.data.records[0].beforeHeld").value(5))
                .andExpect(jsonPath("$.data.records[0].beforeSold").value(30))
                .andExpect(jsonPath("$.data.records[0].afterAvailable").value(20))
                .andExpect(jsonPath("$.data.records[0].afterHeld").value(5))
                .andExpect(jsonPath("$.data.records[0].afterSold").value(30))
                .andExpect(jsonPath("$.data.records[0].operator").value("88001"))
                .andExpect(jsonPath("$.data.records[0].reason").value("补货"))
                .andExpect(jsonPath("$.data.records[0].createdAt").isString());

        // join 缺失（SKU 投影行移除 = 装配数据异常）：库存分页按
        // 数据异常呈现 fail-closed（不静默 null 展示）
        tenantPrivilege.elevated(() -> {
            skuJpaRepository.deleteAll();

        });
        mockMvc.perform(get("/seller/inventory").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.not_found"));
    }

    /**
     * 装配 #1：3 编辑回显 GET 端点形状（SKU 集/规格模板/运费绑定回显）。
     */
    @Test
    @DisplayName("编辑回显三 GET：SKU 集/规格模板/运费绑定形状")
    void productReadback_threeGets() throws Exception {
        final long productId = createDraftViaApi("回显商品");
        final String skuJson = mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":["
                                + "{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final long skuId = extractSkuId(skuJson);
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":8800}"))
                .andExpect(status().isOk());

        // SKU 集回显：价格分透传 + 启用位
        mockMvc.perform(get("/seller/products/" + productId + "/skus")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].specSummary").value("颜色:黑"))
                .andExpect(jsonPath("$.data[0].price").value(8800))
                .andExpect(jsonPath("$.data[0].enabled").value(false));

        // 规格模板回显：维度按配置序
        mockMvc.perform(get("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimensions[0].name").value("颜色"))
                .andExpect(jsonPath("$.data.dimensions[0].values[0]").value("黑"))
                .andExpect(jsonPath("$.data.dimensions[0].values[1]").value("白"));

        // 运费绑定回显：未绑定 null
        mockMvc.perform(get("/seller/products/" + productId + "/freight-template")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.freightTemplateId").doesNotExist());
    }

    /**
     * 装配 #1：租户 fail-closed（S2.3/G1.1 锚点端点面）——B 店铺 token
     * 请求 A 店铺子单详情/发货/库存流水按 404 呈现，归属不泄露；未认证
     * 登录取数 401。
     */
    @Test
    @DisplayName("跨店 fail-closed：详情/发货/库存流水 404 且未认证 401")
    void crossShopFailClosedAndUnauthenticated() throws Exception {
        insertSubOrder(SUB_ORDER_ID, SubOrderStatus.PAID, null);
        insertInventoryRow(SKU_ID, 15, 0, 0);

        // B 店铺查 A 店铺子单详情 → 404
        mockMvc.perform(get("/seller/orders/" + SUB_ORDER_ID)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order.sub_not_found"));
        // B 店铺发货 A 店铺子单 → 404
        mockMvc.perform(post("/seller/sub-orders/" + SUB_ORDER_ID + "/ship")
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"顺丰\",\"trackingNo\":\"SF1\"}"))
                .andExpect(status().isNotFound());
        // B 店铺查 A 店铺库存流水 → 404（归属不泄露）
        mockMvc.perform(get("/seller/inventory/" + SKU_ID + "/logs")
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("inventory.not_found"));
        // B 店铺库存列表 → 空（跨店全集为空）
        mockMvc.perform(get("/seller/inventory").header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 未登录取数 → 401
        mockMvc.perform(get("/seller/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/seller/inventory")).andExpect(status().isUnauthorized());
    }

    /* ================= fixture helpers ================= */

    /**
     * 构造店铺行（global 表，租户中立）。
     */
    private static ShopPO shopPo(long id, String name) {
        final ShopPO po = new ShopPO();
        po.setId(id);
        po.setName(name);
        po.setStatus(ShopStatus.NORMAL);
        return po;
    }

    /**
     * 构造主单行（子单挂载锚点：发货编排 markShipped/订单查询的 master
     * 装载依赖——自足造数，地址/金额快照与子单自洽）。
     */
    private static MasterOrderPO masterPo() {
        final MasterOrderPO po = new MasterOrderPO();
        po.setId(MASTER_ID);
        po.setOrderNo("ORD-SELLER-49");
        po.setBuyerId(88001L);
        po.setRecipient("张三");
        po.setPhone("13800000000");
        po.setProvince("浙江省");
        po.setCity("杭州市");
        po.setDistrict("西湖区");
        po.setDetail("文一西路 1 号");
        po.setGoodsAmount(48000L);
        po.setFreightAmount(800L);
        po.setDiscount(0L);
        po.setPaidAmount(48800L);
        po.setStatus(MasterOrderStatus.PAID);
        return po;
    }

    /**
     * 直插子单行（tenant=SHOP_A；地址/金额快照列与双订单项自洽：
     * 15000×2 + 6000×3 = 48000，运费 800、实付 48800；create_time 由
     * JPA auditing 填充——装载回填实证面）。
     */
    private void insertSubOrder(long subOrderId, SubOrderStatus status, Long waybillId) {
        tenantPrivilege.elevated(() -> {
            final SubOrderPO po = new SubOrderPO();
            po.setId(subOrderId);
            po.setMasterOrderId(MASTER_ID);
            po.setShopId(SHOP_A_ID);
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setSubOrderNo("SO" + subOrderId);
            po.setRecipient("张三");
            po.setPhone("13800000000");
            po.setProvince("浙江省");
            po.setCity("杭州市");
            po.setDistrict("西湖区");
            po.setDetail("文一西路 1 号");
            po.setGoodsAmount(48000L);
            po.setFreightAmount(800L);
            po.setDiscount(0L);
            po.setPaidAmount(48800L);
            po.setStatus(status);
            po.setWaybillId(waybillId);
            subOrderJpaRepository.save(po);

            insertOrderItem(subOrderId, 1L, SKU_ID, 15000L, 2, "/files/p3001.png");
            insertOrderItem(subOrderId, 2L, SKU_ID + 1, 6000L, 3, null);

        });
    }

    /**
     * 直插订单项快照行（tenant=SHOP_A）。
     */
    private void insertOrderItem(long subOrderId, long seq, long skuId, long price,
                                 int qty, String image) {
        final OrderItemPO po = new OrderItemPO();
        po.setId(subOrderId * 10 + seq);
        po.setTenantID(String.valueOf(SHOP_A_ID));
        po.setSubOrderId(subOrderId);
        po.setProductId(PRODUCT_ID);
        po.setSkuId(skuId);
        po.setProductName("测试商品");
        po.setUnitPrice(price);
        po.setQuantity(qty);
        po.setSubtotal(price * qty);
        po.setMainImageUrl(image);
        po.setSpecSummary("颜色:黑,尺码:M");
        orderItemJpaRepository.save(po);
    }

    /**
     * 直插运单行（global 表；含初始轨迹行——运单聚合装载要求轨迹非空）。
     */
    private void insertWaybill(long waybillId, long subOrderId) {
        final WaybillPO po = new WaybillPO();
        po.setId(waybillId);
        po.setSubOrderId(subOrderId);
        po.setCompany("顺丰速运");
        po.setTrackingNo("SF202609080001");
        po.setStatus(WaybillStatus.IN_TRANSIT);
        po.setInTransit(Boolean.TRUE);
        waybillJpaRepository.save(po);

        final WaybillTrackPO track = new WaybillTrackPO();
        track.setId(waybillId * 10);
        track.setWaybillId(waybillId);
        track.setStatus(WaybillStatus.IN_TRANSIT);
        track.setOccurredAt(LocalDateTime.of(2026, 9, 8, 10, 30, 0));
        track.setDescription("运输中");
        waybillTrackJpaRepository.save(track);
    }

    /**
     * 直插库存行（tenant=SHOP_A）与对位 SKU 投影行（join 命中面）。
     */
    private void insertInventoryRow(long skuId, int available, int held, int sold) {
        tenantPrivilege.elevated(() -> {
            final InventoryItemPO po = new InventoryItemPO();
            po.setId(skuId * 10);
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setSkuId(skuId);
            po.setAvailable(available);
            po.setHeld(held);
            po.setSold(sold);
            po.setVersion(0);
            inventoryItemJpaRepository.save(po);
            insertSkuRow(skuId);

        });
    }

    /**
     * 直插 SKU 投影行（tenant=SHOP_A，规格局与库存行对位；商品主体行
     * 已存在时跳过——多 SKU 共享同一商品）。
     */
    private void insertSkuRow(long skuId) {
        final com.nona.inf.persistence.po.catalog.SkuPO po =
                new com.nona.inf.persistence.po.catalog.SkuPO();
        po.setId(skuId);
        po.setTenantID(String.valueOf(SHOP_A_ID));
        po.setProductId(PRODUCT_ID);
        po.setSpecHash("hash-" + skuId);
        po.setSpecSummary("颜色:黑,尺码:M");
        po.setPrice(8800L);
        po.setEnabled(true);
        skuJpaRepository.save(po);
        if (!productJpaRepository.existsById(PRODUCT_ID)) {
            insertProductRow();
        }
    }

    /**
     * 直插商品主体行（tenant=SHOP_A，SKU 投影的商品名 join 面）。
     */
    private void insertProductRow() {
        final com.nona.inf.persistence.po.catalog.ProductPO po =
                new com.nona.inf.persistence.po.catalog.ProductPO();
        po.setId(PRODUCT_ID);
        po.setTenantID(String.valueOf(SHOP_A_ID));
        po.setShopId(SHOP_A_ID);
        po.setName("经典款 T 恤");
        po.setStatus(com.nona.domain.catalog.entity.ProductStatus.ON_SALE);
        productJpaRepository.save(po);
    }

    /**
     * 直插库存流水行（手动调整型；tenant=SHOP_A）。
     */
    private void insertInventoryLogRow(long logId, long skuId) {
        tenantPrivilege.elevated(() -> {
            final InventoryLogPO po = new InventoryLogPO();
            po.setId(logId);
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setSkuId(skuId);
            po.setType(InventoryLogType.PREOCCUPY);
            po.setDelta(3);
            po.setOrderId(75001L);
            po.setBeforeAvailable(12);
            po.setBeforeHeld(2);
            po.setBeforeSold(30);
            po.setAfterAvailable(9);
            po.setAfterHeld(5);
            po.setAfterSold(30);
            po.setOperator(null);
            po.setReason(null);
            inventoryLogJpaRepository.save(po);

        });
    }

    /**
     * 经 API 创建草稿（商家 A）。
     *
     * @param name 商品名称
     * @return 新建商品 ID
     */
    private long createDraftViaApi(String name) throws Exception {
        final String json = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractId(json);
    }

    /**
     * 从响应 JSON 提取数字字段。
     *
     * @param json 响应体
     * @return id 值
     */
    private static long extractId(String json) {
        final int start = json.indexOf("\"id\":") + 5;
        final int end = json.indexOf(',', start);
        return Long.parseLong(json.substring(start, end));
    }

    /**
     * 从 SKU 集响应 JSON 提取首个 SKU ID。
     *
     * @param json 响应体
     * @return 首个 SKU ID
     */
    private static long extractSkuId(String json) {
        final int start = json.indexOf("\"id\":") + 5;
        final int end = json.indexOf(',', start);
        return Long.parseLong(json.substring(start, end));
    }

    /**
     * 构造商家 token。
     *
     * @param uid 账号 ID
     * @return Bearer token
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
    }
}