package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.ShopCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 商家端店铺 REST 端点集成测试：店铺信息查询/编辑与店铺分类 CRUD
 * （真实 Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（详情/编辑/分类增删改/排序递增）、critical（删除后排序不重排）、
 * error（未认证 401、参数校验 400、目标分类不存在 404）、
 * 隔离（跨店铺分类访问按不存在呈现——fail-closed）。
 * 当前店铺从认证上下文取（JWT uid → 用户上下文 shopIds → 请求租户），
 * 不来自请求体。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ShopApiIntegrationTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 71001L;

    /**
     * 商家 B 账号 ID
     */
    private static final long SELLER_B_UID = 71002L;

    /**
     * 店铺 A ID（商家 A 当前店铺，租户锚点）
     */
    private static final long SHOP_A_ID = 90001L;

    /**
     * 店铺 B ID（商家 B 当前店铺，租户锚点）
     */
    private static final long SHOP_B_ID = 90002L;

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
     * 店铺分类子表 JPA（测试数据清理）
     */
    @Autowired
    private ShopCategoryJpaRepository shopCategoryJpaRepository;

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
     * 提权工具（清理 tenant-scoped 分类表需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：清空店铺表并直插 A/B 两家店铺主表行，stub 两个商家上下文
     * （各自当前店铺=店铺 ID）。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            shopCategoryJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
        });
        shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
        shopJpaRepository.save(shopPo(SHOP_B_ID, "店铺B"));
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
    }

    /**
     * happy：查询当前店铺详情 → 信息匹配、状态 NORMAL、初始无分类。
     */
    @Test
    @DisplayName("查询店铺详情返回信息与空分类列表")
    void getShop_returnsDetail() throws Exception {
        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.name").value("店铺A"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.categories").isArray())
                .andExpect(jsonPath("$.data.categories.length()").value(0));
    }

    /**
     * happy：编辑店铺信息 → 生效且状态不变。
     */
    @Test
    @DisplayName("编辑店铺信息生效且状态不变")
    void updateShop_changesInfoKeepsStatus() throws Exception {
        mockMvc.perform(put("/seller/shop")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新店铺名\",\"logo\":\"logo-b.png\",\"description\":\"新简介\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新店铺名"))
                .andExpect(jsonPath("$.data.logo").value("logo-b.png"))
                .andExpect(jsonPath("$.data.description").value("新简介"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"));

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新店铺名"));
    }

    /**
     * happy：新增分类自动分配递增排序（1、2），列表按排序升序返回。
     */
    @Test
    @DisplayName("新增分类排序递增分配")
    void addCategory_assignsIncrementalOrder() throws Exception {
        final Long firstId = addCategory(SELLER_A_UID, "零食");
        final Long secondId = addCategory(SELLER_A_UID, "饮料");

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.categories[0].id").value(firstId))
                .andExpect(jsonPath("$.data.categories[0].name").value("零食"))
                .andExpect(jsonPath("$.data.categories[0].order").value(1))
                .andExpect(jsonPath("$.data.categories[1].id").value(secondId))
                .andExpect(jsonPath("$.data.categories[1].name").value("饮料"))
                .andExpect(jsonPath("$.data.categories[1].order").value(2));
    }

    /**
     * happy：分类改名 → 名称更新、排序保持不变。
     */
    @Test
    @DisplayName("分类改名后名称更新且排序不变")
    void renameCategory_keepsOrder() throws Exception {
        final Long categoryId = addCategory(SELLER_A_UID, "零食");

        mockMvc.perform(put("/seller/shop/categories/" + categoryId)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"休闲零食\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("休闲零食"))
                .andExpect(jsonPath("$.data.order").value(1));

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].name").value("休闲零食"))
                .andExpect(jsonPath("$.data.categories[0].order").value(1));
    }

    /**
     * happy：删除分类 → 剩余分类排序不重排。
     */
    @Test
    @DisplayName("删除分类后其余排序保持")
    void removeCategory_keepsRemainingOrder() throws Exception {
        final Long firstId = addCategory(SELLER_A_UID, "零食");
        addCategory(SELLER_A_UID, "饮料");

        mockMvc.perform(delete("/seller/shop/categories/" + firstId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.categories[0].name").value("饮料"))
                .andExpect(jsonPath("$.data.categories[0].order").value(2));
    }

    /**
     * error：未认证访问 → 401。
     */
    @Test
    @DisplayName("未认证访问店铺接口拒绝")
    void unauthenticated_rejected() throws Exception {
        mockMvc.perform(get("/seller/shop"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * error：空店铺名称编辑 → 400（契约校验）。
     */
    @Test
    @DisplayName("空店铺名称编辑拒绝")
    void updateShop_blankNameRejected() throws Exception {
        mockMvc.perform(put("/seller/shop")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：空分类名称新增 → 400（契约校验）。
     */
    @Test
    @DisplayName("空分类名称新增拒绝")
    void addCategory_blankNameRejected() throws Exception {
        mockMvc.perform(post("/seller/shop/categories")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：改名/删除不存在的分类 → 404。
     */
    @Test
    @DisplayName("操作不存在的分类返回404")
    void renameCategory_missingTarget_returnsNotFound() throws Exception {
        mockMvc.perform(put("/seller/shop/categories/99999")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名字\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/seller/shop/categories/99999")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * critical：商家 B 操作商家 A 的店铺分类 → 404（跨店铺访问按不存在呈现，
     * fail-closed 隔离）。
     */
    @Test
    @DisplayName("跨店铺访问分类不可见（fail-closed）")
    void crossShopCategoryAccess_notVisible() throws Exception {
        final Long categoryId = addCategory(SELLER_A_UID, "A店分类");

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1));

        // 商家 B（店铺 B）访问 A 的分类：改名与删除均按不存在处理
        mockMvc.perform(put("/seller/shop/categories/" + categoryId)
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/seller/shop/categories/" + categoryId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());

        // A 店分类未被越权影响
        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.categories[0].name").value("A店分类"));
    }

    /**
     * critical：商家只能看到自己的店铺（B 视角店铺 A 主表可见但分类为空——
     * 主表为租户锚点 global 展示信息，分类为商家私有数据按店铺租户隔离）。
     */
    @Test
    @DisplayName("商家B的店铺详情不含A店分类")
    void shopBDetail_hasOwnCategoriesOnly() throws Exception {
        addCategory(SELLER_A_UID, "A店分类");
        addCategory(SELLER_B_UID, "B店分类");

        mockMvc.perform(get("/seller/shop").header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(SHOP_B_ID))
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.categories[0].name").value("B店分类"));
    }

    /**
     * 新增分类并返回分类 ID（测试辅助）。
     *
     * @param uid  商家账号 ID
     * @param name 分类名称
     * @return 分类 ID（响应 data.id）
     * @throws Exception MockMvc 调用失败
     */
    private Long addCategory(long uid, String name) throws Exception {
        final String body = mockMvc.perform(post("/seller/shop/categories")
                        .header("Authorization", bearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /**
     * 构造商家 Bearer token。
     *
     * @param uid 账号 ID
     * @return Authorization 头值
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
    }

    /**
     * 构造店铺主表行（global，测试数据）。
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
}