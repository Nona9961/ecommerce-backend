package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端商品草稿 REST 端点集成测试：草稿 CRUD、图片引用管理（增删/设主图）、
 * 自定义属性键值管理（增删改）（真实 Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（创建/详情/列表分页/更新主体/删除、图片增删与设主图、属性增删改）、
 * critical（主图至多一条、主图删除后位空、分页边界）、error（未认证 401、
 * 空名称 400、类目/品牌不存在 404、禁用类目/品牌挂载 400、目标图片/属性
 * 不存在 404、属性键重复 400）、隔离（跨店铺商品访问按不存在呈现——
 * fail-closed）。草稿保存不生效（状态恒 DRAFT——无任何生效路径）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductApiIntegrationTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 72001L;

    /**
     * 商家 B 账号 ID
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
     * 启用平台分类 ID（挂载引用用）
     */
    private static final long CATEGORY_ENABLED = 93001L;

    /**
     * 启用品牌 ID（挂载引用用）
     */
    private static final long BRAND_ENABLED = 94001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 店铺主表 JPA（测试数据写入与清理）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 商品主表 JPA（测试数据清理与断言）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 商品图片子表 JPA（测试数据清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * 商品属性子表 JPA（测试数据清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 平台分类 JPA（挂载引用数据准备）
     */
    @Autowired
    private PlatformCategoryJpaRepository categoryJpaRepository;

    /**
     * 品牌 JPA（挂载引用数据准备）
     */
    @Autowired
    private BrandJpaRepository brandJpaRepository;

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
     * 提权工具（清理 tenant-scoped 商品表需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 商品编辑版本子表 JPA（测试数据清理——写路径留痕行随用例销毁）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 每用例前：清空商品相关表（含 global 的类目/品牌引用表）并直插
     * A/B 两家店铺、一个启用类目、一个启用品牌，stub 两个商家上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
        });
        categoryJpaRepository.deleteAll();
        brandJpaRepository.deleteAll();
        shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
        shopJpaRepository.save(shopPo(SHOP_B_ID, "店铺B"));
        categoryJpaRepository.save(categoryPo(CATEGORY_ENABLED, "数码"));
        brandJpaRepository.save(brandPo(BRAND_ENABLED, "示例"));
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
    }

    /**
     * happy：创建草稿（名称必填）→ 名称/描述/类目/品牌落位，状态恒 DRAFT
     * （草稿可保存不生效）。
     */
    @Test
    @DisplayName("创建草稿落位主体字段与 DRAFT 状态")
    void createDraft_returnsDraftDetail() throws Exception {
        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"无线耳机\",\"description\":\"主动降噪\","
                                + "\"categoryId\":" + CATEGORY_ENABLED + ",\"brandId\":" + BRAND_ENABLED + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("无线耳机"))
                .andExpect(jsonPath("$.data.description").value("主动降噪"))
                .andExpect(jsonPath("$.data.categoryId").value(CATEGORY_ENABLED))
                .andExpect(jsonPath("$.data.brandId").value(BRAND_ENABLED))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.images.length()").value(0))
                .andExpect(jsonPath("$.data.attributes.length()").value(0));
    }

    /**
     * error：空名称创建拒绝（400）；类目不存在 404；品牌不存在 404。
     */
    @Test
    @DisplayName("空名称/不存在类目/不存在品牌的创建拒绝")
    void createDraft_rejectsInvalidBody() throws Exception {
        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"categoryId\":99999}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"brandId\":99999}"))
                .andExpect(status().isNotFound());
    }

    /**
     * error：禁用类目/禁用品脾挂载拒绝（400）。
     */
    @Test
    @DisplayName("禁用类目/品牌不可挂载")
    void createDraft_rejectsDisabledReferences() throws Exception {
        categoryJpaRepository.save(categoryPo(93002L, "图书", false));
        brandJpaRepository.save(brandPo(94002L, "停用", false));

        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"categoryId\":93002}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"brandId\":94002}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * happy：详情回读；error：跨店铺详情按不存在呈现（404，fail-closed）。
     */
    @Test
    @DisplayName("详情回读完整且跨店铺按不存在呈现")
    void getProduct_detailAndCrossShop404() throws Exception {
        final long productId = createDraft("无线耳机");

        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(productId))
                .andExpect(jsonPath("$.data.name").value("无线耳机"));

        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * happy：列表分页（新商品在前，total 准确）。
     */
    @Test
    @DisplayName("列表分页返回翻页结果")
    void listDrafts_returnsPagedResult() throws Exception {
        createDraft("商品一");
        createDraft("商品二");
        createDraft("商品三");

        mockMvc.perform(get("/seller/products?pageNum=1&pageSize=2")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].status").value("DRAFT"));
    }

    /**
     * happy：更新主体（改名/换引用/清引用生效）。
     */
    @Test
    @DisplayName("更新主体生效：改名/换引用/清引用")
    void updateProduct_appliesBodyChanges() throws Exception {
        final long productId = createDraft("旧名");

        mockMvc.perform(put("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\",\"description\":\"新描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新名"))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    /**
     * happy：删除草稿后详情 404。
     */
    @Test
    @DisplayName("删除草稿后不可见")
    void deleteProduct_removesDraft() throws Exception {
        final long productId = createDraft("待删");

        mockMvc.perform(delete("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * happy：图片管理——添加（默认非主图）→ 添加主图（替换原主图）→
     * 删除主图（位空）。
     */
    @Test
    @DisplayName("图片增删与设主图闭环")
    void imageLifecycle_addSetRemove() throws Exception {
        final long productId = createDraft("有图商品");

        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/a.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("/files/a.png"))
                .andExpect(jsonPath("$.data.primary").value(false));

        final String firstImageJson = mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/b.png\",\"primary\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.primary").value(true))
                .andReturn().getResponse().getContentAsString();
        final long firstImageId = extractId(firstImageJson);

        mockMvc.perform(put("/seller/products/" + productId + "/images/" + firstImageId + "/primary")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstImageId))
                .andExpect(jsonPath("$.data.primary").value(true));

        mockMvc.perform(delete("/seller/products/" + productId + "/images/" + firstImageId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.images[0].primary").value(false));
    }

    /**
     * error：空 URL 图片 400；目标图片不存在（详情/删除/设主图）404。
     */
    @Test
    @DisplayName("空URL与不存在图片的操作拒绝")
    void imageLifecycle_rejectsInvalidOps() throws Exception {
        final long productId = createDraft("有图商品");

        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/seller/products/" + productId + "/images/99999")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/" + productId + "/images/99999/primary")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * happy：属性管理——增删改闭环；error：空键 400、重复键 400、目标不存在 404。
     */
    @Test
    @DisplayName("属性增删改闭环与非法操作拒绝")
    void attributeLifecycle_crudAndRejections() throws Exception {
        final long productId = createDraft("带属性商品");

        final String addJson = mockMvc.perform(post("/seller/products/" + productId + "/attributes")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"材质\",\"value\":\"纯棉\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("材质"))
                .andReturn().getResponse().getContentAsString();
        final long attributeId = extractId(addJson);

        mockMvc.perform(put("/seller/products/" + productId + "/attributes/" + attributeId)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"面料\",\"value\":\"棉100%\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("面料"))
                .andExpect(jsonPath("$.data.value").value("棉100%"));

        mockMvc.perform(post("/seller/products/" + productId + "/attributes")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"面料\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/seller/products/" + productId + "/attributes")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/seller/products/" + productId + "/attributes/99999")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/" + productId + "/attributes/" + attributeId)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"面料\",\"value\":\"棉\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("棉"));
        mockMvc.perform(delete("/seller/products/" + productId + "/attributes/" + attributeId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.length()").value(0));
    }

    /**
     * error：未认证 401；商家 A 不可跨店更新/删除 B 店商品（404）。
     */
    @Test
    @DisplayName("未认证拒绝且跨店更新删除按不存在呈现")
    void crossShopAndAuthFailClosed() throws Exception {
        final long productId = createDraft("A店商品");

        mockMvc.perform(post("/seller/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"篡改\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/seller/products/" + productId + "/images")
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"/files/x.png\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * 构造商品 token。
     *
     * @param uid 账号 ID
     * @return Bearer token
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
    }

    /**
     * 通过 API 创建草稿（商家 A）。
     *
     * @param name 商品名称
     * @return 新建商品 ID
     */
    private long createDraft(String name) throws Exception {
        final String json = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractId(json);
    }

    /**
     * 从响应体提取数据的 id 字段。
     *
     * @param json 响应体
     * @return id 值
     */
    private long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /**
     * 构造店铺主表行。
     *
     * @param id   店铺 ID
     * @param name 店铺名称
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
     * 构造启用平台分类行。
     *
     * @param id   分类 ID
     * @param name 分类名称
     * @return 分类 PO
     */
    private static PlatformCategoryPO categoryPo(long id, String name) {
        return categoryPo(id, name, true);
    }

    /**
     * 构造平台分类行（可指定状态）。
     *
     * @param id      分类 ID
     * @param name    分类名称
     * @param enabled 是否启用
     * @return 分类 PO
     */
    private static PlatformCategoryPO categoryPo(long id, String name, boolean enabled) {
        final PlatformCategoryPO po = new PlatformCategoryPO();
        po.setId(id);
        po.setName(name);
        po.setOrderNo(1);
        po.setStatus(enabled ? CategoryStatus.ENABLED : CategoryStatus.DISABLED);
        return po;
    }

    /**
     * 构造启用品牌行。
     *
     * @param id   品牌 ID
     * @param name 品牌名称
     * @return 品牌 PO
     */
    private static com.nona.inf.persistence.po.catalog.BrandPO brandPo(long id, String name) {
        return brandPo(id, name, true);
    }

    /**
     * 构造品牌行（可指定状态）。
     *
     * @param id      品牌 ID
     * @param name    品牌名称
     * @param enabled 是否启用
     * @return 品牌 PO
     */
    private static com.nona.inf.persistence.po.catalog.BrandPO brandPo(long id, String name, boolean enabled) {
        final com.nona.inf.persistence.po.catalog.BrandPO po = new com.nona.inf.persistence.po.catalog.BrandPO();
        po.setId(id);
        po.setName(name);
        po.setStatus(enabled ? BrandStatus.ENABLED : BrandStatus.DISABLED);
        return po;
    }
}