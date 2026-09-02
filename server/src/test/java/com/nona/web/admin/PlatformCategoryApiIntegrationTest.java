package com.nona.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopCategoryJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台分类 REST 端点集成测试：分类 CRUD + 禁用/启用（真实 Security 链 + H2 + 全局表）。
 * <p>
 * 平台运营身份以测试 token（uid + ADMIN portal）+ mock 用户上下文（ADMIN 角色）
 * 模拟（与入驻审核端点同模式）。覆盖：happy（创建/自动排序/显式排序/列表按序/
 * 状态过滤/更新/禁用/启用/删除=禁用软删）、critical（改回自身原名合法、排序空缺保持、
 * 重复禁用幂等）、error（撞名 400、复用禁用态名称 400、不存在 404、空名称 400、
 * 非法状态参数 400、未认证 401、商家跨端 403）、独立性（与商家店铺分类同名共存）、并发（同名校验恰一成功）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class PlatformCategoryApiIntegrationTest {

    /**
     * 测试平台运营账号 ID（无库内落点，仅令牌主体）
     */
    private static final long ADMIN_UID = 9001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 平台分类 JPA 仓储（测试数据清理与直接断言）
     */
    @Autowired
    private PlatformCategoryJpaRepository categoryRepository;

    /**
     * 店铺分类 JPA 仓储（独立性验证的数据准备与清理）
     */
    @Autowired
    private ShopCategoryJpaRepository shopCategoryRepository;

    /**
     * 提权工具（写入 tenant-scoped 店铺分类行需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * JWT 签发器（构造平台/商家令牌）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前：清空平台分类表与店铺分类表，模拟平台运营身份。
     */
    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
        tenantPrivilege.elevated(() -> {
            shopCategoryRepository.deleteAll();
        });
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    /**
     * happy：创建——排序自动分配（最大 + 1，从 1 起）与显式排序双路径，状态 ENABLED。
     */
    @Test
    @DisplayName("创建分类自动分配排序或采用显式排序")
    void create_autoAndExplicitOrder() throws Exception {
        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("数码"))
                .andExpect(jsonPath("$.data.order").value(1))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"图书\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order").value(2));

        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"家电\",\"order\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order").value(9));

        assertThat(categoryRepository.count()).isEqualTo(3);
    }

    /**
     * happy：列表按展示排序升序返回。
     */
    @Test
    @DisplayName("列表按展示排序升序")
    void list_orderedByOrderAsc() throws Exception {
        createAs("家电", 9);
        createAs("数码", 1);
        createAs("图书", 2);

        mockMvc.perform(get("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].name").value("数码"))
                .andExpect(jsonPath("$.data[1].name").value("图书"))
                .andExpect(jsonPath("$.data[2].name").value("家电"));
    }

    /**
     * happy：状态过滤（DISABLED 仅含禁用分类，ENABLED 不含禁用分类）。
     */
    @Test
    @DisplayName("状态过滤只返回对应状态分类")
    void list_filterByStatus() throws Exception {
        final long categoryId = createAs("数码", 1);
        createAs("图书", 2);
        mockMvc.perform(post("/admin/categories/" + categoryId + "/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/categories")
                        .param("status", "DISABLED")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("数码"));

        mockMvc.perform(get("/admin/categories")
                        .param("status", "ENABLED")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("图书"));
    }

    /**
     * happy：更新——改名 + 重排序生效。
     */
    @Test
    @DisplayName("更新改名与排序生效")
    void update_renameAndReorder() throws Exception {
        final long categoryId = createAs("数码", 1);

        mockMvc.perform(put("/admin/categories/" + categoryId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"消费电子\",\"order\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("消费电子"))
                .andExpect(jsonPath("$.data.order").value(5));
    }

    /**
     * critical：改回自身原名合法（名称唯一性守卫排除自身）。
     */
    @Test
    @DisplayName("改回自身原名合法")
    void update_sameNameAllowed() throws Exception {
        final long categoryId = createAs("数码", 1);

        mockMvc.perform(put("/admin/categories/" + categoryId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("数码"));
    }

    /**
     * critical：更新时排序空缺保持原排序。
     */
    @Test
    @DisplayName("更新排序空缺保持原值")
    void update_orderOmittedKeepsOrder() throws Exception {
        final long categoryId = createAs("数码", 3);

        mockMvc.perform(put("/admin/categories/" + categoryId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order").value(3));
    }

    /**
     * happy：禁用/启用循环（禁用→DISABLED，启用→ENABLED，重复禁用幂等）。
     */
    @Test
    @DisplayName("禁用启用循环流转")
    void disable_enable_cycle() throws Exception {
        final long categoryId = createAs("数码", 1);
        final String adminToken = adminToken();

        mockMvc.perform(post("/admin/categories/" + categoryId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/categories/" + categoryId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/categories/" + categoryId + "/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(categoryRepository.findById(categoryId).orElseThrow().getStatus().name())
                .isEqualTo("ENABLED");
    }

    /**
     * happy：删除 = 禁用软删——行保留、状态 DISABLED、可再启用恢复（「删除」）。
     */
    @Test
    @DisplayName("删除为禁用软删且行保留")
    void delete_disablesAndKeepsRow() throws Exception {
        final long categoryId = createAs("数码", 1);
        final String adminToken = adminToken();

        mockMvc.perform(delete("/admin/categories/" + categoryId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(categoryRepository.findById(categoryId)).isPresent();
        assertThat(categoryRepository.findById(categoryId).orElseThrow().getStatus().name())
                .isEqualTo("DISABLED");

        mockMvc.perform(post("/admin/categories/" + categoryId + "/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(categoryRepository.findById(categoryId).orElseThrow().getStatus().name())
                .isEqualTo("ENABLED");
    }

    /**
     * error：同名创建被拒（400 catalog.category_name_conflict）。
     */
    @Test
    @DisplayName("同名创建冲突")
    void create_duplicateNameConflict() throws Exception {
        createAs("数码", 1);

        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.category_name_conflict"));
    }

    /**
     * critical：禁用态名称不可复用（全局唯一含禁用态，软删不释放名称）。
     */
    @Test
    @DisplayName("禁用态名称不可复用")
    void create_reuseDisabledNameConflict() throws Exception {
        final long categoryId = createAs("数码", 1);
        mockMvc.perform(post("/admin/categories/" + categoryId + "/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.category_name_conflict"));
    }

    /**
     * error：更新撞其他分类名称被拒（400）。
     */
    @Test
    @DisplayName("更新撞名冲突")
    void update_conflictingNameRejected() throws Exception {
        createAs("数码", 1);
        final long otherId = createAs("图书", 2);

        mockMvc.perform(put("/admin/categories/" + otherId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.category_name_conflict"));
    }

    /**
     * happy：与商家店铺分类相互独立——与既有店铺分类同名创建平台分类成功。
     */
    @Test
    @DisplayName("与商家店铺分类同名共存互不影响")
    void create_sameNameAsShopCategoryAllowed() throws Exception {
        tenantPrivilege.elevated(() -> {
            final ShopCategoryPO shopCategory = new ShopCategoryPO();
            shopCategory.setId(55001L);
            shopCategory.setTenantID("90001");
            shopCategory.setShopId(90001L);
            shopCategory.setName("数码");
            shopCategory.setOrderNo(1);
            shopCategoryRepository.save(shopCategory);
        });

        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("数码"));

        assertThat(tenantPrivilege.elevated(() -> shopCategoryRepository.count())).isEqualTo(1);
        assertThat(categoryRepository.count()).isEqualTo(1);
    }

    /**
     * error：未认证 401；商家令牌访问平台接口 → 403。
     */
    @Test
    @DisplayName("未认证 401 且商家跨端 403")
    void auth_unauthorizedAndWrongRole() throws Exception {
        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isUnauthorized());

        final String sellerToken = tokenFor(registerSellerId());
        mockMvc.perform(get("/admin/categories").header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * error：更新/禁用/启用/删除不存在的分类 → 404。
     */
    @Test
    @DisplayName("操作不存在分类返回 404")
    void operatons_onMissingCategoryReturn404() throws Exception {
        final String adminToken = adminToken();

        mockMvc.perform(put("/admin/categories/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.category_not_found"));

        mockMvc.perform(post("/admin/categories/999999/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.category_not_found"));

        mockMvc.perform(post("/admin/categories/999999/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/admin/categories/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    /**
     * error：空名称创建被拒（JSR-380，400 generic.validation_failed）。
     */
    @Test
    @DisplayName("空名称创建返回 400")
    void create_blankNameRejected() throws Exception {
        mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    /**
     * error：非法状态过滤参数 → 400 generic.validation_failed。
     */
    @Test
    @DisplayName("非法状态过滤参数返回 400")
    void list_invalidStatusRejected() throws Exception {
        mockMvc.perform(get("/admin/categories")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    /**
     * 并发：两个请求同时创建同名分类——守卫先查后插存在竞态窗口，
     * 数据库唯一约束兜底保证恰有一个成功（另一个以冲突 400 呈现）。
     */
    @Test
    @DisplayName("并发同名创建恰一成功")
    void concurrentCreate_sameNameExactlyOneSucceeds() throws Exception {
        final String adminToken = adminToken();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger conflicts = new AtomicInteger();

        final Runnable create = () -> invoke(adminToken,
                post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"并发同名校验\"}"),
                successes, conflicts);
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                create.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                create.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(categoryRepository.count()).isEqualTo(1);
    }

    /**
     * 执行单个创建请求并按结果分类计数。
     */
    private void invoke(String token,
                        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                        AtomicInteger successes,
                        AtomicInteger conflicts) {
        try {
            final MvcResult result = mockMvc.perform(builder.header("Authorization", "Bearer " + token)).andReturn();
            if (result.getResponse().getStatus() == 200) {
                successes.incrementAndGet();
            } else if (result.getResponse().getStatus() == 400) {
                conflicts.incrementAndGet();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 创建分类并返回分类 ID。
     *
     * @param name  分类名称
     * @param order 展示排序（大于 0 才显式传入）
     * @return 分类 ID
     */
    private long createAs(String name, int order) throws Exception {
        final String body = order > 0
                ? "{\"name\":\"" + name + "\",\"order\":" + order + "}"
                : "{\"name\":\"" + name + "\"}";
        final MvcResult result = mockMvc.perform(post("/admin/categories")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    /**
     * 注册商家并返回账号 ID。
     *
     * @return 商家账号 ID
     */
    private long registerSellerId() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"seller-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"SELLER\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("userId").asText());
    }

    /**
     * 商家账号令牌。
     *
     * @param accountId 账号 ID
     * @return JWT
     */
    private String tokenFor(long accountId) {
        return tokenProvider.issueToken(accountId, Portal.SELLER);
    }

    /**
     * 平台运营令牌（测试身份：uid + ADMIN portal；角色经 mock 用户上下文提供）。
     *
     * @return JWT
     */
    private String adminToken() {
        return tokenProvider.issueToken(ADMIN_UID, Portal.ADMIN);
    }
}