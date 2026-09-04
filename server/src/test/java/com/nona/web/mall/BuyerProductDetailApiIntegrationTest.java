package com.nona.web.mall;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 买家商品详情 REST 端点集成测试（详情读 + 主库强一致 + @CrossTenant
 * 显式放行形态——真实 Security 链 + JWT + H2 + 买家视角（tenant 空））。
 * <p>
 * 覆盖：happy（在售详情 200：主体/主图/属性/SKU 价格与可售量 zip/店铺
 * 卡片）、critical（部分 SKU 无库存行按可售 0 呈现）、error（草稿/待审/
 * 已下架/不存在统一 404——下架后买家不可见、未认证 401）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class BuyerProductDetailApiIntegrationTest {

    /**
     * 买家账号 ID
     */
    private static final long BUYER_UID = 52001L;

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
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    @Autowired
    private SkuJpaRepository skuJpaRepository;

    @Autowired
    private ShopJpaRepository shopJpaRepository;

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
        shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(BUYER_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    // ---- Happy path ----

    /**
     * happy：在售商品详情 200（主体/主图/属性/店铺 + SKU 价格
     * 与可售量随动数据——买家视角 tenant 空，@CrossTenant 放行后跨租户
     * 读生效）。
     */
    @Test
    @DisplayName("在售商品详情完整返回")
    void detail_onSale_fullView() throws Exception {
        final long productId = onSaleProductAs("详情商品");
        stockRowAs(firstSkuIdOf(productId), 5);

        mockMvc.perform(get("/mall/products/" + productId)
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.name").value("详情商品"))
                .andExpect(jsonPath("$.data.mainImageUrl").value("/files/a.png"))
                .andExpect(jsonPath("$.data.skus.length()").value(1))
                .andExpect(jsonPath("$.data.skus[0].price").value(1999))
                .andExpect(jsonPath("$.data.skus[0].available").value(5))
                .andExpect(jsonPath("$.data.shop.shopId").value(SHOP_A_ID));
    }

    /**
     * critical：无库存行的 SKU 按可售 0 呈现（无库存 SKU 置灰——
     * 未初始化/缺行统一 0，zip 语义）。
     */
    @Test
    @DisplayName("无库存行SKU可售按0呈现")
    void detail_skuWithoutStock_availableZero() throws Exception {
        final long productId = onSaleProductAs("缺库存商品");

        mockMvc.perform(get("/mall/products/" + productId)
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].available").value(0));
    }

    // ---- Error path ----

    /**
     * error：草稿商品详情按不存在呈现（404——未审核生效内容买家不可见）。
     */
    @Test
    @DisplayName("草稿商品详情404")
    void detail_draft_404() throws Exception {
        final long productId = draftProductAs("草稿商品");

        mockMvc.perform(get("/mall/products/" + productId)
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：待审核商品详情按不存在呈现（404——提交后买家不可见直至
     * 审核通过）。
     */
    @Test
    @DisplayName("待审核商品详情404")
    void detail_pending_404() throws Exception {
        final long productId = pendingProductAs("待审商品");

        mockMvc.perform(get("/mall/products/" + productId)
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：已下架商品详情按不存在呈现（404——手动下架生效买家不可见）。
     */
    @Test
    @DisplayName("已下架商品详情404")
    void detail_delisted_404() throws Exception {
        final long productId = delistedRowAs("已下架商品");

        mockMvc.perform(get("/mall/products/" + productId)
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：不存在商品 404。
     */
    @Test
    @DisplayName("不存在商品详情404")
    void detail_missing_404() throws Exception {
        mockMvc.perform(get("/mall/products/999999")
                        .header("Authorization", bearer(BUYER_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：未认证请求 401（安全链统一拦截）。
     */
    @Test
    @DisplayName("未认证详情请求401")
    void detail_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/mall/products/1"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 构建辅助 ----

    /**
     * 请求头构造（Bearer 令牌）。
     *
     * @param uid 账号 ID
     * @return Authorization 头值
     */
    private String bearer(long uid) {
        final Portal portal = uid == ADMIN_UID ? Portal.ADMIN
                : (uid == BUYER_UID ? Portal.MALL : Portal.SELLER);
        return "Bearer " + tokenProvider.issueToken(uid, portal);
    }

    /**
     * 建草稿（HTTP 链路）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long draftProductAs(String name) throws Exception {
        final MvcResult created = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
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
     * 建待审核商品（单 SKU 定价启用 + 主图 + 提交）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long pendingProductAs(String name) throws Exception {
        final long productId = draftProductAs(name);
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isOk());
        final long skuId = firstSkuIdOf(productId);
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
     * 直插已下架商品行（详情不可见断言 fixture——生命周期经审核无法
     * 到达 DELISTED 的捷径：直插合法持久化形态）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long delistedRowAs(String name) throws Exception {
        return tenantPrivilege.elevated(() -> {
            final ProductPO po = new ProductPO();
            po.setId(IDUtils.generateID());
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setShopId(SHOP_A_ID);
            po.setName(name);
            po.setDescription("描述");
            po.setCategoryId(CATEGORY_ENABLED);
            po.setBrandId(BRAND_ENABLED);
            po.setStatus(ProductStatus.DELISTED);
            productJpaRepository.save(po);
            return po.getId();
        });
    }

    /**
     * 首 SKU ID（提权读）。
     *
     * @param productId 商品 ID
     * @return SKU ID
     */
    private long firstSkuIdOf(long productId) throws Exception {
        return tenantPrivilege.elevated(
                () -> skuJpaRepository.findByProductIdOrderByIdAsc(productId)).get(0).getId();
    }

    /**
     * 直插库存行（可售量就位；详情可售量 zip 断言输入）。
     *
     * @param skuId     SKU ID
     * @param available 可售量
     */
    private void stockRowAs(long skuId, int available) throws Exception {
        tenantPrivilege.elevated(() -> {
            final InventoryItemPO po = new InventoryItemPO();
            po.setId(IDUtils.generateID());
            po.setTenantID(String.valueOf(SHOP_A_ID));
            po.setSkuId(skuId);
            po.setAvailable(available);
            po.setHeld(0);
            po.setSold(0);
            po.setVersion(0);
            inventoryItemJpaRepository.save(po);
            return null;
        });
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

    /**
     * 构造店铺行（global 表——店铺卡片读面 fixture：商品归属店铺行必在）。
     *
     * @param id   店铺 ID
     * @param name 店铺名
     * @return 店铺 PO
     */
    private static ShopPO shopPo(long id, String name) {
        final ShopPO po = new ShopPO();
        po.setId(id);
        po.setName(name);
        po.setStatus(ShopStatus.NORMAL);
        return po;
    }
}
