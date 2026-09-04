package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopCategoryJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端商品-店铺分类绑定 REST 端点集成测试（WU-19：S7.1 商品侧
 * 多对多绑定/解绑/回显 + 分类删除引用守卫——真实 Security 链 + JWT +
 * H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（整体替换绑定多分类 / 回显 / 差集收敛 / 清空）、error
 * （跨店铺分类绑定 404 / 删除有绑定商品的分类 409 / 不存在商品 404）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductShopCategoryBindingApiIntegrationTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 72001L;

    /**
     * 店铺 A ID（租户锚点）
     */
    private static final long SHOP_A_ID = 92001L;

    /**
     * 店铺 B ID（跨店隔离断言）
     */
    private static final long SHOP_B_ID = 92002L;

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
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    @Autowired
    private PlatformCategoryJpaRepository categoryJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ShopJpaRepository shopJpaRepository;

    @Autowired
    private ShopCategoryJpaRepository shopCategoryJpaRepository;

    @MockitoBean
    private AuthUserCache authUserCache;

    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
            shopCategoryJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
        });
        categoryJpaRepository.deleteAll();
        brandJpaRepository.deleteAll();
        categoryJpaRepository.save(categoryPo(CATEGORY_ENABLED));
        brandJpaRepository.save(brandPo(BRAND_ENABLED));
        tenantPrivilege.elevated(() -> {
            shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
            shopJpaRepository.save(shopPo(SHOP_B_ID, "店铺B"));
            shopCategoryJpaRepository.save(shopCategoryPo(81001L, SHOP_A_ID, "新品"));
            shopCategoryJpaRepository.save(shopCategoryPo(81002L, SHOP_A_ID, "热卖"));
            shopCategoryJpaRepository.save(shopCategoryPo(81003L, SHOP_A_ID, "清仓"));
            shopCategoryJpaRepository.save(shopCategoryPo(82001L, SHOP_B_ID, "他店分类"));
        });
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
    }

    // ---- Happy path ----

    /**
     * happy：整体替换绑定两个本店分类 → 200 返回绑定条目（含名称，
     * 按绑定序）。
     */
    @Test
    @DisplayName("整体替换绑定多个店铺分类")
    void updateShopCategories_binds() throws Exception {
        final long productId = draftProductAs("绑定商品");

        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[81001,81002]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(81001))
                .andExpect(jsonPath("$.data[0].name").value("新品"))
                .andExpect(jsonPath("$.data[1].id").value(81002));
    }

    /**
     * happy：绑定回显（S07 编辑页多选勾选初始值）——GET 返回绑定条目。
     */
    @Test
    @DisplayName("绑定回显列表")
    void listShopCategories_returnsBound() throws Exception {
        final long productId = draftProductAs("回显商品");
        bindAs(productId, List.of(81001L, 81003L));

        mockMvc.perform(get("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(81001))
                .andExpect(jsonPath("$.data[1].id").value(81003));
    }

    /**
     * happy：再次整体替换收敛为提交集（表单保存幂等——差集由服务端
     * 计算）。
     */
    @Test
    @DisplayName("再次整体替换收敛")
    void updateShopCategories_replaceConverges() throws Exception {
        final long productId = draftProductAs("收敛商品");
        bindAs(productId, List.of(81001L, 81002L, 81003L));

        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[81002]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(81002));
    }

    /**
     * happy：清空绑定（空集合=全解绑）→ 200 空列表。
     */
    @Test
    @DisplayName("清空全部绑定")
    void updateShopCategories_clearAll() throws Exception {
        final long productId = draftProductAs("清空商品");
        bindAs(productId, List.of(81001L, 81002L));

        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * happy：无绑定商品回显空列表。
     */
    @Test
    @DisplayName("无绑定回显空列表")
    void listShopCategories_empty() throws Exception {
        final long productId = draftProductAs("无绑定商品");

        mockMvc.perform(get("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- Error path ----

    /**
     * error：跨店铺分类绑定按不存在呈现（404——店铺归属校验 fail-closed，
     * 不泄露他店分类存在性）。
     */
    @Test
    @DisplayName("跨店铺分类绑定404")
    void updateShopCategories_otherShopCategory_404() throws Exception {
        final long productId = draftProductAs("跨店分类商品");

        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[82001]}"))
                .andExpect(status().isNotFound());
    }

    /**
     * error：不存在的分类按不存在呈现（404）。
     */
    @Test
    @DisplayName("不存在分类绑定404")
    void updateShopCategories_missingCategory_404() throws Exception {
        final long productId = draftProductAs("缺分类商品");

        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[999999]}"))
                .andExpect(status().isNotFound());
    }

    /**
     * error：删除有商品绑定的店铺分类拒绝（409 冲突——S7.1 引用守卫：
     * 删除前分类必须零商品绑定，有绑定先解绑再删）。
     */
    @Test
    @DisplayName("删除有绑定的分类拒绝409")
    void removeCategory_withBoundProduct_409() throws Exception {
        final long productId = draftProductAs("守卫商品");
        bindAs(productId, List.of(81001L));

        mockMvc.perform(delete("/seller/shop/categories/81001")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isConflict());
    }

    /**
     * error：不存在商品绑定 404。
     */
    @Test
    @DisplayName("不存在商品绑定404")
    void updateShopCategories_missingProduct_404() throws Exception {
        mockMvc.perform(put("/seller/products/999999/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[81001]}"))
                .andExpect(status().isNotFound());
    }

    /**
     * error：未认证请求 401。
     */
    @Test
    @DisplayName("未认证绑定请求401")
    void updateShopCategories_unauthenticated_401() throws Exception {
        mockMvc.perform(put("/seller/products/1/shop-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":[81001]}"))
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
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
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
     * 绑定（PUT 端点辅助：整体替换商品店铺分类绑定集）。
     *
     * @param productId      商品 ID
     * @param shopCategoryIds 目标分类 ID 集合
     */
    private void bindAs(long productId, List<Long> shopCategoryIds) throws Exception {
        final String ids = shopCategoryIds.stream()
                .map(String::valueOf).reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]").orElse("[]");
        mockMvc.perform(put("/seller/products/" + productId + "/shop-categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopCategoryIds\":" + ids + "}"))
                .andExpect(status().isOk());
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
     * 构造店铺行（global 表）。
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

    /**
     * 构造店铺分类行（tenant 表）。
     *
     * @param id      分类 ID
     * @param shopId  店铺 ID
     * @param name    分类名
     * @return 分类 PO
     */
    private static ShopCategoryPO shopCategoryPo(long id, long shopId, String name) {
        final ShopCategoryPO po = new ShopCategoryPO();
        po.setId(id);
        po.setTenantID(String.valueOf(shopId));
        po.setShopId(shopId);
        po.setName(name);
        po.setOrderNo((int) id % 100);
        return po;
    }
}
