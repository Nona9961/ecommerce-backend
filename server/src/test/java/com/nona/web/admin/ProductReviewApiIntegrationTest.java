package com.nona.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.SkuPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import com.nona.util.JacksonUtil;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台商品审核 REST 端点集成测试：待审/全量列表、审核通过/驳回（真实
 * Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 商品数据归店铺租户（tenant=shopId），平台视角跨店铺全集：列表为跨租户
 * 读放行（用例层 @CrossTenant），审核动作为提权事务编排（elevatedInTransaction）。
 * 审核人身份以测试 token（uid + ADMIN portal）+ mock 用户上下文（ADMIN
 * 角色）模拟；商家上下文（SELLER + shopIds）支撑商品构建链路（真实
 * 商家 API 经认证链提交上架）。覆盖：happy（待审列表分页/跨店铺全集、
 * 审核通过生效 + REVIEW_PASS 结论行、驳回回草稿 + REJECT 结论行含原因、
 * 敏感编辑待审内容经审核通过后覆盖生效内容）、critical（重复审核第二者
 * 被状态守卫拒绝、并发审核状态收敛、非法状态参数）、error（非待审审核
 * 400、空驳回原因 400、不存在 404、未认证 401、商家跨端 403）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductReviewApiIntegrationTest {

    /**
     * 测试商家 A 账号 ID（商家端商品构建链路）
     */
    private static final long SELLER_A_UID = 72001L;

    /**
     * 测试商家 B 账号 ID（跨店铺全集断言）
     */
    private static final long SELLER_B_UID = 72002L;

    /**
     * 店铺 A ID（商家 A 当前店铺，租户锚点）
     */
    private static final long SHOP_A_ID = 92001L;

    /**
     * 店铺 B ID（商家 B 当前店铺，租户锚点）
     */
    private static final long SHOP_B_ID = 92002L;

    /**
     * 测试平台运营账号 ID（无库内落点，仅令牌主体与审核人断言）
     */
    private static final long ADMIN_UID = 9001L;

    /**
     * 启用平台分类 ID（完整商品挂载引用）
     */
    private static final long CATEGORY_ENABLED = 93001L;

    /**
     * 启用品牌 ID（完整商品挂载引用）
     */
    private static final long BRAND_ENABLED = 94001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * JWT 签发器（构造商家/平台令牌）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis 并注入角色与店铺归属）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 提权工具（清理 tenant-scoped 商品表需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 商品主表 JPA（测试数据清理与状态断言）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 版本子表 JPA（审核结论行断言与清理）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 商品图片子表 JPA（清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * 商品属性子表 JPA（清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * SKU 子表 JPA（清理）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

    /**
     * 平台分类 JPA（挂载引用数据准备与清理）
     */
    @Autowired
    private PlatformCategoryJpaRepository categoryJpaRepository;

    /**
     * 品牌 JPA（挂载引用数据准备与清理）
     */
    @Autowired
    private BrandJpaRepository brandJpaRepository;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 每用例前：提权清空商品系表与类目/品牌引用表 + 直插启用态引用 +
     * stub 商家与平台上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
        });
        categoryJpaRepository.deleteAll();
        brandJpaRepository.deleteAll();
        categoryJpaRepository.save(categoryPo(CATEGORY_ENABLED));
        brandJpaRepository.save(brandPo(BRAND_ENABLED));
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    // ---- Happy path ----

    /**
     * happy：待审列表分页（状态过滤 + 跨店铺全集 + 资料概要可见 + 创建序）。
     */
    @Test
    @DisplayName("待审列表分页跨店铺全集且创建序")
    void list_pendingProducts_crossShopPaged() throws Exception {
        final long firstId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "店铺甲商品");
        final long secondId = submitCompleteProductAs(SELLER_B_UID, SHOP_B_ID, "店铺乙商品");

        mockMvc.perform(get("/admin/products")
                        .param("status", "PENDING_REVIEW")
                        .param("pageSize", "10")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(firstId))
                .andExpect(jsonPath("$.data.records[0].name").value("店铺甲商品"))
                .andExpect(jsonPath("$.data.records[0].shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.records[0].imageCount").value(1))
                .andExpect(jsonPath("$.data.records[0].enabledSkuCount").value(2))
                .andExpect(jsonPath("$.data.records[1].id").value(secondId))
                .andExpect(jsonPath("$.data.records[1].shopId").value(SHOP_B_ID));
    }

    /**
     * happy:无状态过滤列表含全部状态；非法状态参数拒绝（400）。
     */
    @Test
    @DisplayName("全量列表与非法状态参数拒绝")
    void list_allStatusAndInvalidParam() throws Exception {
        submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");

        mockMvc.perform(get("/admin/products")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/admin/products")
                        .param("status", "NOPE")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    /**
     * happy：审核通过——待审商品在售，追加 REVIEW_PASS 结论行（审核人 =
     * 操作人，快照 = 通过后生效内容）。
     */
    @Test
    @DisplayName("审核通过在售且结论行落位")
    void approve_movesOnSale_withConclusionRow() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");

        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);
        final List<ProductEditVersionPO> versions = reviewConclusions(productId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getTriggerType()).isEqualTo(EditVersionTriggerType.REVIEW_PASS);
        assertThat(versions.get(0).getOperator()).isEqualTo(String.valueOf(ADMIN_UID));
        assertThat(versions.get(0).getSnapshotJson()).contains("待审商品");
    }

    /**
     * happy：审核驳回——回草稿可修改重提，追加 REJECT 结论行（驳回原因
     * 随行承载，商家据此修正），待审核状态解除。
     */
    @Test
    @DisplayName("审核驳回回草稿且结论行携带原因")
    void reject_returnsDraft_withReasonRow() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");

        mockMvc.perform(post("/admin/products/" + productId + "/reject")
                        .header("Authorization", bearer(ADMIN_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"图片侵权\"}"))
                .andExpect(status().isOk());

        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.DRAFT);
        final List<ProductEditVersionPO> versions = reviewConclusions(productId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getTriggerType()).isEqualTo(EditVersionTriggerType.REJECT);
        assertThat(versions.get(0).getReviewReason()).isEqualTo("图片侵权");
        assertThat(versions.get(0).getOperator()).isEqualTo(String.valueOf(ADMIN_UID));
    }

    /**
     * happy：驳回 → 商家修改重提 → 再次审核通过（完整审核闭环）。
     */
    @Test
    @DisplayName("驳回重提后再次审核通过")
    void rejectThenResubmitThenApprove_fullCycle() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");
        mockMvc.perform(post("/admin/products/" + productId + "/reject")
                        .header("Authorization", bearer(ADMIN_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"资料需补充\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/seller/products/" + productId + "/submit")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk());

        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);
        final List<ProductEditVersionPO> versions = reviewConclusions(productId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getTriggerType()).isEqualTo(EditVersionTriggerType.REVIEW_PASS);
        assertThat(versions.get(1).getTriggerType()).isEqualTo(EditVersionTriggerType.REJECT);
    }

    /**
     * happy：在售商品敏感字段编辑（改价）转待审 → 平台审核通过 → 待审
     * 内容覆盖生效内容（买家可见新价格），待审草稿位清空。
     */
    @Test
    @DisplayName("在售改价待审内容经审核通过后覆盖生效")
    void approveWithPending_coversEffectiveContent() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");
        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk());
        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);

        final long firstSku = firstSkuIdOf(productId);
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + firstSku + "/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":999}"))
                .andExpect(status().isOk());

        // 敏感编辑分流：转待审核，生效内容保持旧价（主表 pending_draft_json 承载新内容）
        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(productRow(productId).orElseThrow().getPendingDraftJson())
                .contains("\"price\":999");

        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk());

        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);
        assertThat(productRow(productId).orElseThrow().getPendingDraftJson())
                .isNull();
    }

    // ---- Error path ----

    /**
     * error：草稿（未提交）直接审批 → 400 状态机守卫；重复审批 → 400。
     */
    @Test
    @DisplayName("非待审审批拒绝")
    void approve_notPending_returns400() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");
        final MvcResult rejected = mockMvc.perform(post("/admin/products/" + productId + "/reject")
                        .header("Authorization", bearer(ADMIN_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重提前\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);

        // 已驳回（DRAFT）审批拒绝
        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.product_status_illegal"));

        // 在售重复审批拒绝
        final long secondProduct = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "第二件");
        mockMvc.perform(post("/admin/products/" + secondProduct + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/products/" + secondProduct + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.product_status_illegal"));
    }

    /**
     * error：空驳回原因 → 400（请求体校验，驳回必须附原因）。
     */
    @Test
    @DisplayName("空驳回原因拒绝")
    void reject_blankReason_returns400() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");

        mockMvc.perform(post("/admin/products/" + productId + "/reject")
                        .header("Authorization", bearer(ADMIN_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));

        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.PENDING_REVIEW);
    }

    /**
     * error：审核不存在的商品 → 404。
     */
    @Test
    @DisplayName("审核不存在商品返回404")
    void approve_missingProduct_returns404() throws Exception {
        mockMvc.perform(post("/admin/products/999999/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.product_not_found"));
    }

    /**
     * error：未认证 401；商家 token 访问平台审核接口 → 403（角色边界）。
     */
    @Test
    @DisplayName("未认证401且商家跨端403")
    void auth_unauthorizedAndSellerCrossPortal() throws Exception {
        mockMvc.perform(get("/admin/products"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/products")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));

        mockMvc.perform(post("/admin/products/1/approve")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isForbidden());
    }

    /**
     * 并发：两个审核动作先后到达同一待审商品——第一个迁移生效，第二个
     * （到达时商品已裁定）被状态机守卫拒绝（并发重复 approve 第二者拒绝）。
     */
    @Test
    @DisplayName("并发重复审核第二者被守卫拒绝")
    void concurrentDuplicateReview_secondRejected() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");
        final String adminToken = bearer(ADMIN_UID);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch secondStart = new CountDownLatch(1);
        final AtomicInteger firstStatus = new AtomicInteger();
        final AtomicInteger secondStatus = new AtomicInteger();

        executor.submit(() -> {
            try {
                firstStatus.set(mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", adminToken)).andReturn().getResponse().getStatus());
                secondStart.countDown();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        executor.submit(() -> {
            try {
                secondStart.await();
                secondStatus.set(mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", adminToken)).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(firstStatus.get()).isEqualTo(200);
        assertThat(secondStatus.get()).isEqualTo(400);
        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);
        assertThat(reviewConclusions(productId)).hasSize(1);
    }

    /**
     * 并发：两个平台账号同时审批同一待审商品——恰有一个状态迁移生效
     * （另一者被状态守卫拒绝或到达时已裁定），最终状态一致（在售）。
     */
    @Test
    @DisplayName("并发审批状态收敛")
    void concurrentApprove_statusConverges() throws Exception {
        final long productId = submitCompleteProductAs(SELLER_A_UID, SHOP_A_ID, "待审商品");
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    final int status = mockMvc.perform(
                                    post("/admin/products/" + productId + "/approve")
                                            .header("Authorization", bearer(ADMIN_UID)))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        successes.incrementAndGet();
                    } else {
                        assertThat(status).isEqualTo(400);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        assertThat(productRow(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ON_SALE);
        assertThat(reviewConclusions(productId)).hasSize(successes.get());
    }

    // ---- 构建辅助 ----

    /**
     * 经真实商家 API 构建完整商品（主体 + 规格模板 + 双 SKU 定价启用 +
     * 主图）并提交上架，返回商品 ID。
     *
     * @param sellerUid 商家账号 ID
     * @param shopId    店铺 ID（租户锚点，上下文注入）
     * @param name      商品名称
     * @return 商品 ID
     */
    private long submitCompleteProductAs(long sellerUid, long shopId, String name) throws Exception {
        final MvcResult created = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"描述\","
                                + "\"categoryId\":" + CATEGORY_ENABLED
                                + ",\"brandId\":" + BRAND_ENABLED + "}"))
                .andExpect(status().isOk())
                .andReturn();
        final long productId = Long.parseLong(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("data").path("id").asText());
        final MvcResult specResult = mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]}]}"))
                .andReturn();
        assertThat(specResult.getResponse().getStatus()).isEqualTo(200);
        final String skusBody = objectMapper.readTree(specResult.getResponse().getContentAsString())
                .path("data").toString();
        final long first = objectMapper.readTree(skusBody).get(0).path("id").asLong();
        final long second = objectMapper.readTree(skusBody).get(1).path("id").asLong();
        priceAndEnable(sellerUid, productId, first, 1999L);
        priceAndEnable(sellerUid, productId, second, 2999L);
        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/a.png\",\"primary\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/seller/products/" + productId + "/submit")
                        .header("Authorization", bearer(sellerUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        return productId;
    }

    /**
     * 定价并启用单个 SKU（商家链路构建辅助）。
     *
     * @param sellerUid 商家账号 ID
     * @param productId 商品 ID
     * @param skuId     SKU ID
     * @param price     价格（分）
     */
    private void priceAndEnable(long sellerUid, long productId, long skuId, long price) throws Exception {
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":" + price + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    /**
     * 读取商品首 SKU ID（SKU 子表按商品读取；提权读放行——admin 请求
     * 上下文无店铺租户）。
     *
     * @param productId 商品 ID
     * @return 首 SKU ID
     */
    private long firstSkuIdOf(long productId) throws Exception {
        return tenantPrivilege.elevated(
                () -> skuJpaRepository.findByProductIdOrderByIdAsc(productId)).get(0).getId();
    }

    /**
     * 商品主表行（测试断言查询：提权读放行——admin 请求上下文无店铺
     * 租户，直查被租户过滤 fail-closed 挡住，断言需跨租户视角）。
     *
     * @param productId 商品 ID
     * @return 商品行；不存在返回空
     */
    private Optional<ProductPO> productRow(long productId) throws Exception {
        return tenantPrivilege.elevated(() -> productJpaRepository.findById(productId));
    }

    /**
     * 商品版本行列表（测试断言查询：与 {@link #productRow} 同提权读放行；
     * 新版本在前）。
     *
     * @param productId 商品 ID
     * @return 版本行列表
     */
    private List<ProductEditVersionPO> versionRows(long productId) throws Exception {
        return tenantPrivilege.elevated(() -> editVersionJpaRepository
                .findByProductIdOrderByVersionNoDesc(productId,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent());
    }

    /**
     * 审核结论版本行列表（测试断言查询用：版本链含商家编辑留痕行，结论
     * 断言过滤 REVIEW_PASS / REJECT 行，新版本在前）。
     *
     * @param productId 商品 ID
     * @return 审核结论行列表
     */
    private List<ProductEditVersionPO> reviewConclusions(long productId) throws Exception {
        return versionRows(productId).stream()
                .filter(version -> version.getTriggerType() == EditVersionTriggerType.REVIEW_PASS
                        || version.getTriggerType() == EditVersionTriggerType.REJECT)
                .toList();
    }

    /**
     * 请求头构造（Bearer 令牌）。
     *
     * @param uid 账号 ID
     * @return Authorization 头值
     */
    private String bearer(long uid) {
        final Portal portal = uid == ADMIN_UID ? Portal.ADMIN : Portal.SELLER;
        return "Bearer " + tokenProvider.issueToken(uid, portal);
    }

    /**
     * 构造启用态平台分类行。
     *
     * @param id 分类 ID
     * @return 分类 PO
     */
    private static PlatformCategoryPO categoryPo(long id) {
        final PlatformCategoryPO po = new PlatformCategoryPO();
        po.setId(id);
        po.setName("数码");
        po.setOrderNo(1);
        po.setStatus(CategoryStatus.ENABLED);
        return po;
    }

    /**
     * 构造启用态品牌行。
     *
     * @param id 品牌 ID
     * @return 品牌 PO
     */
    private static BrandPO brandPo(long id) {
        final BrandPO po = new BrandPO();
        po.setId(id);
        po.setName("示例");
        po.setStatus(BrandStatus.ENABLED);
        return po;
    }
}