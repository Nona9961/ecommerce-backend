package com.nona.application.support;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 售罄自动下架编排集成测试（inventory SelloutEvent 消费 → catalog
 * 自动下架，真实 Spring 上下文 + H2 + 租户提权编排）。
 * <p>
 * 编排契约：skuId → 商品定位 → 非 ON_SALE 静默跳过 → 全部启用 SKU
 * 可售均为 0 才下架（部分售罄不下架）→ 提权事务内聚合迁移落库。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductAutoDelistIntegrationAcTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 72001L;

    /**
     * 平台审核员账号 ID
     */
    private static final long ADMIN_UID = 9001L;

    /**
     * 店铺 A ID（租户锚点）
     */
    private static final long SHOP_A_ID = 92001L;

    /**
     * 启用平台分类 ID
     */
    private static final long CATEGORY_ENABLED = 93001L;

    /**
     * 启用品牌 ID
     */
    private static final long BRAND_ENABLED = 94001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TenantPrivilege tenantPrivilege;

    @Autowired
    private ProductAutoDelistListener listener;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    @Autowired
    private SkuJpaRepository skuJpaRepository;

    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    @Autowired
    private PlatformCategoryJpaRepository categoryJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @MockitoBean
    private AuthUserCache authUserCache;

    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
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
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
        // 事件消费编排（autoDelistOnSellout 直接调用）在测试线程执行：
        // 调用点以 TrackingContext.withScope 绑定跟踪作用域（编排内部自管
        // 提权事务与放行，不依赖请求租户）。
    }

    // ---- Happy path ----

    /**
     * happy：唯一启用 SKU 售罄（available=0 且 held=0）→ 商品自动下架
     * （DELISTED）。
     */
    @Test
    @DisplayName("全部启用SKU售罄自动下架")
    void sellout_autoDelist() throws Exception {
        final long productId = onSaleProductAs("自动下架商品");
        final long skuId = firstSkuIdOf(productId);
        stockRowAs(skuId, 0, 0);

        TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuId));

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.DELISTED);
    }

    // ---- Critical path ----

    /**
     * critical：商品仅部分 SKU 售罄 → 保持 ON_SALE（部分售罄只置灰，
     * 商品继续在售——全 SKU 售罄才下架的判定边界）。
     */
    @Test
    @DisplayName("部分SKU售罄不下架")
    void sellout_partialStock_onSaleKept() throws Exception {
        final long productId = onSaleProductAs("部分售罄商品");
        final List<Long> skuIds = skuIdsOf(productId);
        stockRowAs(skuIds.get(0), 0, 0);
        stockRowAs(skuIds.get(1), 5, 0);

        TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuIds.get(0)));

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ON_SALE);
    }

    /**
     * critical：多 SKU 依次售罄——最后一个 SKU 售罄事件到达才触发下架
     * （事件序无关：消费时点按当前库存判定）。
     */
    @Test
    @DisplayName("多SKU全部售罄后下架")
    void sellout_allSkusSoldOut_delisted() throws Exception {
        final long productId = onSaleProductAs("全部售罄商品");
        final List<Long> skuIds = skuIdsOf(productId);
        stockRowAs(skuIds.get(0), 0, 0);
        stockRowAs(skuIds.get(1), 0, 0);

        TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuIds.get(0)));

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.DELISTED);
    }

    /**
     * critical：补货后旧事件到达（消费时点已恢复可售）→ 不下架（避免
     * 旧事件误杀补货商品）。
     */
    @Test
    @DisplayName("补货后旧事件不误下架")
    void sellout_restockedBeforeConsume_keptOnSale() throws Exception {
        final long productId = onSaleProductAs("补货商品");
        final long skuId = firstSkuIdOf(productId);
        stockRowAs(skuId, 8, 0);

        TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuId));

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ON_SALE);
    }

    // ---- Error path ----

    /**
     * error：重复消费幂等——已下架商品再收事件静默跳过（不抛异常、
     * 状态保持 DELISTED）。
     */
    @Test
    @DisplayName("已下架商品重复事件静默跳过")
    void sellout_alreadyDelisted_idempotent() throws Exception {
        final long productId = onSaleProductAs("幂等商品");
        final long skuId = firstSkuIdOf(productId);
        stockRowAs(skuId, 0, 0);
        TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuId));
        assertThat(statusOf(productId)).isEqualTo(ProductStatus.DELISTED);

        assertThatCode(() -> TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuId))).doesNotThrowAnyException();

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.DELISTED);
    }

    /**
     * error：SKU 不存在（已删除/脏事件）静默跳过（不抛异常）。
     */
    @Test
    @DisplayName("SKU不存在静默跳过")
    void sellout_missingSku_silent() {
        assertThatCode(() -> TrackingContext.withScope(() -> listener.autoDelistOnSellout(999999L))).doesNotThrowAnyException();
    }

    /**
     * error：待审核商品收到售罄事件静默跳过（状态守卫——非 ON_SALE 不
     * 迁移；PENDING 分流期缺口登记为契约决策待拍板项）。
     */
    @Test
    @DisplayName("待审核商品事件静默跳过")
    void sellout_pendingProduct_silent() throws Exception {
        final long productId = pendingProductAs("待审售罄商品");
        final long skuId = firstSkuIdOf(productId);
        stockRowAs(skuId, 0, 0);

        assertThatCode(() -> TrackingContext.withScope(() -> listener.autoDelistOnSellout(skuId))).doesNotThrowAnyException();

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.PENDING_REVIEW);
    }

    // ---- 构建辅助 ----

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
     * 建草稿（HTTP 链路）。
     *
     * @param sellerUid 商家账号
     * @param name      商品名
     * @return 商品 ID
     */
    private long draftProductAs(long sellerUid, String name) throws Exception {
        final MvcResult created = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"描述\","
                                + "\"categoryId\":" + CATEGORY_ENABLED
                                + ",\"brandId\":" + BRAND_ENABLED + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    /**
     * 建待审核商品（双 SKU 规格：黑白——多 SKU 判定场景用）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long pendingProductAs(String name) throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, name);
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]}]}"))
                .andExpect(status().isOk());
        for (final long skuId : skuIdsOf(productId)) {
            mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                            .header("Authorization", bearer(SELLER_A_UID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"price\":1999}"))
                    .andExpect(status().isOk());
            mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                            .header("Authorization", bearer(SELLER_A_UID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":true}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/a.png\",\"primary\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/seller/products/" + productId + "/submit")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk());
        return productId;
    }

    /**
     * 建在售商品（待审核 + 平台审核通过）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long onSaleProductAs(String name) throws Exception {
        final long productId = pendingProductAs(name);
        mockMvc.perform(post("/admin/products/" + productId + "/approve")
                        .header("Authorization", bearer(ADMIN_UID)))
                .andExpect(status().isOk());
        return productId;
    }

    /**
     * 商品全部 SKU ID（提权读——断言查询无请求租户）。
     *
     * @param productId 商品 ID
     * @return SKU ID 列表（ID 升序≈创建序）
     */
    private List<Long> skuIdsOf(long productId) throws Exception {
        return tenantPrivilege.elevated(
                () -> skuJpaRepository.findByProductIdOrderByIdAsc(productId))
                .stream().map(sku -> sku.getId()).toList();
    }

    /**
     * 首 SKU ID。
     *
     * @param productId 商品 ID
     * @return 首 SKU ID
     */
    private long firstSkuIdOf(long productId) throws Exception {
        return skuIdsOf(productId).get(0);
    }

    /**
     * 直插库存行（提权写——三态 + 版本齐备；租户=商品店铺）。
     *
     * @param skuId     SKU ID
     * @param available 可售量
     * @param held      预占量
     */
    private void stockRowAs(long skuId, int available, int held) throws Exception {
        tenantPrivilege.elevated(() -> {
            final InventoryItemPO po = new InventoryItemPO();
            po.setId(com.nona.util.IDUtils.generateID());
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setSkuId(skuId);
            po.setAvailable(available);
            po.setHeld(held);
            po.setSold(0);
            po.setVersion(0);
            inventoryItemJpaRepository.save(po);
            return null;
        });
    }

    /**
     * 商品当前状态（提权读——无请求租户断言查询）。
     *
     * @param productId 商品 ID
     * @return 商品状态
     */
    private ProductStatus statusOf(long productId) throws Exception {
        return tenantPrivilege.elevated(() -> productJpaRepository.findById(productId))
                .map(ProductPO::getStatus).orElseThrow();
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
