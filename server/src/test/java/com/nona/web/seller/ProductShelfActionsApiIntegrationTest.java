package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductImagePO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.SkuPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端商品上下架 + 运费模板绑定 REST 端点集成测试：手动下架 /
 * 重新上架回迁 / 商品绑模板（真实 Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（在售下架 / 下架重新上架 / 绑模板）、critical（上架-下架
 * 闭环）、error（草稿下架 400 / 跨店 404 / 待审下架 400 / 未认证 401 /
 * 跨店模板 404）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductShelfActionsApiIntegrationTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 72001L;

    /**
     * 商家 B 账号 ID（跨店隔离断言）
     */
    private static final long SELLER_B_UID = 72002L;

    /**
     * 平台审核员账号 ID
     */
    private static final long ADMIN_UID = 9001L;

    /**
     * 店铺 A ID（商家 A 当前店铺，租户锚点）
     */
    private static final long SHOP_A_ID = 92001L;

    /**
     * 店铺 B ID
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
    private FreightTemplateJpaRepository freightTemplateJpaRepository;

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
            freightTemplateJpaRepository.deleteAll();
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
     * happy：在售商品手动下架 → 200 状态 DELISTED（下架生效）。
     */
    @Test
    @DisplayName("在售商品手动下架成功")
    void delist_onSale_delisted() throws Exception {
        final long productId = onSaleProductAs("下架目标");

        mockMvc.perform(post("/seller/products/" + productId + "/delist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELISTED"));
    }

    /**
     * happy：已下架商品重新上架 → 200 状态 ON_SALE（免重审回迁）。
     */
    @Test
    @DisplayName("已下架商品重新上架成功")
    void relist_delisted_onSale() throws Exception {
        final long productId = delistedRowAs(SHOP_A_ID, "重新上架目标");

        mockMvc.perform(post("/seller/products/" + productId + "/relist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
    }

    /**
     * happy：绑定运费模板 → 200（模板属于本店；商品-模板引用承载）。
     */
    @Test
    @DisplayName("绑定本店运费模板成功")
    void bindFreightTemplate_ownTemplate_ok() throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, SHOP_A_ID, "绑模板商品");
        final long templateId = freightRowAs(SHOP_A_ID);

        mockMvc.perform(put("/seller/products/" + productId + "/freight-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freightTemplateId\":" + templateId + "}"))
                .andExpect(status().isOk());
    }

    /**
     * happy：解绑运费模板（null=清空引用）→ 200。
     */
    @Test
    @DisplayName("解绑运费模板成功")
    void bindFreightTemplate_null_unbind() throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, SHOP_A_ID, "解绑模板商品");

        mockMvc.perform(put("/seller/products/" + productId + "/freight-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freightTemplateId\":null}"))
                .andExpect(status().isOk());
    }

    // ---- Critical path ----

    /**
     * critical：重新上架后再次下架闭环（DELISTED → ON_SALE → DELISTED
     * 往返，状态机可逆对）。
     */
    @Test
    @DisplayName("重新上架后可再次下架")
    void relistThenDelist_roundTrip() throws Exception {
        final long productId = delistedRowAs(SHOP_A_ID, "往返商品");
        mockMvc.perform(post("/seller/products/" + productId + "/relist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        mockMvc.perform(post("/seller/products/" + productId + "/delist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELISTED"));
    }

    // ---- Error path ----

    /**
     * error：草稿商品下架拒绝（400 非法状态——未生效内容无下架语义）。
     */
    @Test
    @DisplayName("草稿商品下架拒绝")
    void delist_draft_rejected() throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, SHOP_A_ID, "草稿商品");

        mockMvc.perform(post("/seller/products/" + productId + "/delist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：待审核商品下架拒绝（400——待审期无下架入口）。
     */
    @Test
    @DisplayName("待审核商品下架拒绝")
    void delist_pending_rejected() throws Exception {
        final long productId = pendingProductAs("待审商品");

        mockMvc.perform(post("/seller/products/" + productId + "/delist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：在售商品重复上架拒绝（400——在售即上架态）。
     */
    @Test
    @DisplayName("在售商品重新上架拒绝")
    void relist_onSale_rejected() throws Exception {
        final long productId = onSaleProductAs("在售上架目标");

        mockMvc.perform(post("/seller/products/" + productId + "/relist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：跨店铺商品下架按不存在呈现（404——fail-closed 不泄露归属）。
     */
    @Test
    @DisplayName("跨店铺下架按不存在呈现")
    void delist_otherShop_404() throws Exception {
        final long productId = onSaleProductAs("他店商品");

        mockMvc.perform(post("/seller/products/" + productId + "/delist")
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：不存在商品下架 404。
     */
    @Test
    @DisplayName("不存在商品下架404")
    void delist_missing_404() throws Exception {
        mockMvc.perform(post("/seller/products/999999/delist")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * error：未认证请求 401（安全链统一拦截）。
     */
    @Test
    @DisplayName("未认证下架请求401")
    void delist_unauthenticated_401() throws Exception {
        mockMvc.perform(post("/seller/products/1/delist"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * error：跨店铺模板绑定按不存在呈现（404——模板归属校验 fail-closed）。
     */
    @Test
    @DisplayName("跨店铺运费模板绑定404")
    void bindFreightTemplate_otherShopTemplate_404() throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, SHOP_A_ID, "跨店模板商品");
        final long otherTemplateId = freightRowAs(SHOP_B_ID);

        mockMvc.perform(put("/seller/products/" + productId + "/freight-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freightTemplateId\":" + otherTemplateId + "}"))
                .andExpect(status().isNotFound());
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
     * @param shopId    店铺 ID
     * @param name      商品名
     * @return 商品 ID
     */
    private long draftProductAs(long sellerUid, long shopId, String name) throws Exception {
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
     * 建待审核商品（草稿 + 规格 SKU 定价启用 + 主图 + 提交）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long pendingProductAs(String name) throws Exception {
        final long productId = draftProductAs(SELLER_A_UID, SHOP_A_ID, name);
        completeProductAs(SELLER_A_UID, productId);
        mockMvc.perform(post("/seller/products/" + productId + "/submit")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk());
        return productId;
    }

    /**
     * 建在售商品（草稿 + 完整内容 + 提交 + 平台审核通过）。
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
     * 商品内容补齐（规格模板 + 全 SKU 定价启用 + 主图）。
     *
     * @param sellerUid 商家账号
     * @param productId 商品 ID
     */
    private void completeProductAs(long sellerUid, long productId) throws Exception {
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isOk());
        final long skuId = skuIdOf(productId);
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1999}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(sellerUid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/a.png\",\"primary\":true}"))
                .andExpect(status().isOk());
    }

    /**
     * 首 SKU ID（提权读——断言查询无请求租户）。
     *
     * @param productId 商品 ID
     * @return SKU ID
     */
    private long skuIdOf(long productId) throws Exception {
        return tenantPrivilege.elevated(
                () -> skuJpaRepository.findByProductIdOrderByIdAsc(productId)).get(0).getId();
    }

    /**
     * 直插已下架商品行（状态机经审核无法到达 DELISTED 的 fixture
     * 捷径：直插「曾审核通过后下架的完整商品」持久化形态——主表行 +
     * 规格模板 JSON + 定价启用 SKU 行 + 主图行，等价于 ON_SALE 下架
     * 后的真实形态，relist 完整性防御校验可通过）。
     *
     * @param shopId 店铺 ID
     * @param name   商品名
     * @return 商品 ID
     */
    private long delistedRowAs(long shopId, String name) throws Exception {
        return tenantPrivilege.elevated(() -> {
            final ProductPO po = new ProductPO();
            po.setId(IDUtils.generateID());
            po.setTenantID(String.valueOf(shopId));
            po.setShopId(shopId);
            po.setName(name);
            po.setDescription("描述");
            po.setCategoryId(CATEGORY_ENABLED);
            po.setBrandId(BRAND_ENABLED);
            po.setStatus(ProductStatus.DELISTED);
            po.setSpecTemplateJson("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}");
            productJpaRepository.save(po);
            final SkuPO skuPo = new SkuPO();
            skuPo.setId(IDUtils.generateID());
            skuPo.setTenantID(String.valueOf(shopId));
            skuPo.setProductId(po.getId());
            skuPo.setSpecHash("hash-delisted");
            skuPo.setSpecSummary("颜色:黑");
            skuPo.setPrice(1999L);
            skuPo.setEnabled(true);
            skuJpaRepository.save(skuPo);
            final ProductImagePO imagePo = new ProductImagePO();
            imagePo.setId(IDUtils.generateID());
            imagePo.setTenantID(String.valueOf(shopId));
            imagePo.setProductId(po.getId());
            imagePo.setUrl("/files/a.png");
            imagePo.setPrimary(true);
            imageJpaRepository.save(imagePo);
            return po.getId();
        });
    }

    /**
     * 直插运费模板行（PER_ITEM 规则，归一化参数齐备）。
     *
     * @param shopId 店铺 ID
     * @return 模板 ID
     */
    private long freightRowAs(long shopId) throws Exception {
        return tenantPrivilege.elevated(() -> {
            final FreightTemplatePO po = new FreightTemplatePO();
            po.setId(IDUtils.generateID());
            po.setTenantID(String.valueOf(shopId));
            po.setShopId(shopId);
            po.setName("按件模板");
            po.setRuleType(FreightRuleType.PER_ITEM);
            po.setPerItemPrice(800L);
            po.setStatus(FreightTemplateStatus.ENABLED);
            freightTemplateJpaRepository.save(po);
            return po.getId();
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
}
