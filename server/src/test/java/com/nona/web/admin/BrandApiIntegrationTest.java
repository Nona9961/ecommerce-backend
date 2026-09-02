package com.nona.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
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
 * 品牌库 REST 端点集成测试：品牌 CRUD + 禁用/启用（真实 Security 链 + H2 + 全局表）。
 * <p>
 * 平台运营身份以测试 token（uid + ADMIN portal）+ mock 用户上下文（ADMIN 角色）
 * 模拟（与入驻审核端点同模式）。覆盖：happy（创建含/不含 logo、列表按创建序、
 * 状态过滤、更新名称与 logo、禁用/启用、删除=禁用软删）、critical（改回自身原名合法、
 * logo 清除、重复禁用幂等）、error（撞名 400、复用禁用态名称 400、不存在 404、
 * 空名称 400、非法状态参数 400、未认证 401、商家跨端 403）、
 * 并发（同名校验恰一成功——数据库唯一约束兜底）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class BrandApiIntegrationTest {

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
     * 品牌 JPA 仓储（测试数据清理与直接断言）
     */
    @Autowired
    private BrandJpaRepository brandRepository;

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
     * 每用例前：清空品牌表，模拟平台运营身份。
     */
    @BeforeEach
    void setUp() {
        brandRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(Optional.empty());
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    /**
     * happy：创建——名称与 logo 透传，状态 ENABLED。
     */
    @Test
    @DisplayName("创建品牌成功且状态为 ENABLED")
    void create_withLogo() throws Exception {
        mockMvc.perform(post("/admin/brands")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\",\"logo\":\"logo-nike.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("耐克"))
                .andExpect(jsonPath("$.data.logo").value("logo-nike.png"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        assertThat(brandRepository.count()).isEqualTo(1);
    }

    /**
     * happy：logo 缺省创建成功（logo 可空）。
     */
    @Test
    @DisplayName("无 logo 创建成功")
    void create_withoutLogo() throws Exception {
        mockMvc.perform(post("/admin/brands")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"无印良品\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("无印良品"))
                .andExpect(jsonPath("$.data.logo").doesNotExist());
    }

    /**
     * happy：列表按创建序（ID 升序）返回。
     */
    @Test
    @DisplayName("列表按创建序返回")
    void list_orderedByIdAsc() throws Exception {
        createAs("耐克", "logo-a.png");
        createAs("阿迪达斯", null);
        createAs("李宁", "logo-c.png");

        mockMvc.perform(get("/admin/brands")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].name").value("耐克"))
                .andExpect(jsonPath("$.data[1].name").value("阿迪达斯"))
                .andExpect(jsonPath("$.data[2].name").value("李宁"));
    }

    /**
     * happy：状态过滤（DISABLED 仅含禁用品牌）。
     */
    @Test
    @DisplayName("状态过滤只返回对应状态品牌")
    void list_filterByStatus() throws Exception {
        final long brandId = createAs("耐克", null);
        createAs("李宁", null);
        mockMvc.perform(post("/admin/brands/" + brandId + "/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/brands")
                        .param("status", "DISABLED")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("耐克"));

        mockMvc.perform(get("/admin/brands")
                        .param("status", "ENABLED")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("李宁"));
    }

    /**
     * happy：更新——名称与 logo 更新生效。
     */
    @Test
    @DisplayName("更新名称与 logo 生效")
    void update_renameAndChangeLogo() throws Exception {
        final long brandId = createAs("耐克", "logo-a.png");

        mockMvc.perform(put("/admin/brands/" + brandId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克中国\",\"logo\":\"logo-b.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("耐克中国"))
                .andExpect(jsonPath("$.data.logo").value("logo-b.png"));
    }

    /**
     * critical：更新缺省 logo 字段即清除（logo 可空）。
     */
    @Test
    @DisplayName("更新缺省 logo 清除")
    void update_omittedLogoClears() throws Exception {
        final long brandId = createAs("耐克", "logo-a.png");

        mockMvc.perform(put("/admin/brands/" + brandId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logo").doesNotExist());
    }

    /**
     * critical：改回自身原名合法（名称唯一性守卫排除自身）。
     */
    @Test
    @DisplayName("改回自身原名合法")
    void update_sameNameAllowed() throws Exception {
        final long brandId = createAs("耐克", null);

        mockMvc.perform(put("/admin/brands/" + brandId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("耐克"));
    }

    /**
     * happy：禁用/启用循环（禁用→DISABLED，启用→ENABLED，重复禁用幂等）。
     */
    @Test
    @DisplayName("禁用启用循环流转")
    void disable_enable_cycle() throws Exception {
        final long brandId = createAs("耐克", null);
        final String adminToken = adminToken();

        mockMvc.perform(post("/admin/brands/" + brandId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/brands/" + brandId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/brands/" + brandId + "/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(brandRepository.findById(brandId).orElseThrow().getStatus().name())
                .isEqualTo("ENABLED");
    }

    /**
     * happy：删除 = 禁用软删——行保留、状态 DISABLED、可再启用恢复（「删除」）。
     */
    @Test
    @DisplayName("删除为禁用软删且行保留")
    void delete_disablesAndKeepsRow() throws Exception {
        final long brandId = createAs("耐克", null);
        final String adminToken = adminToken();

        mockMvc.perform(delete("/admin/brands/" + brandId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(brandRepository.findById(brandId)).isPresent();
        assertThat(brandRepository.findById(brandId).orElseThrow().getStatus().name())
                .isEqualTo("DISABLED");

        mockMvc.perform(post("/admin/brands/" + brandId + "/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(brandRepository.findById(brandId).orElseThrow().getStatus().name())
                .isEqualTo("ENABLED");
    }

    /**
     * error：同名创建被拒（400 catalog.brand_name_conflict）。
     */
    @Test
    @DisplayName("同名创建冲突")
    void create_duplicateNameConflict() throws Exception {
        createAs("耐克", null);

        mockMvc.perform(post("/admin/brands")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.brand_name_conflict"));
    }

    /**
     * critical：禁用态名称不可复用（全局唯一含禁用态，软删不释放名称）。
     */
    @Test
    @DisplayName("禁用态名称不可复用")
    void create_reuseDisabledNameConflict() throws Exception {
        final long brandId = createAs("耐克", null);
        mockMvc.perform(post("/admin/brands/" + brandId + "/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/brands")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.brand_name_conflict"));
    }

    /**
     * error：更新撞其他品牌名称被拒（400）。
     */
    @Test
    @DisplayName("更新撞名冲突")
    void update_conflictingNameRejected() throws Exception {
        createAs("耐克", null);
        final long otherId = createAs("李宁", null);

        mockMvc.perform(put("/admin/brands/" + otherId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.brand_name_conflict"));
    }

    /**
     * error：未认证 401；商家令牌访问平台接口 → 403。
     */
    @Test
    @DisplayName("未认证 401 且商家跨端 403")
    void auth_unauthorizedAndWrongRole() throws Exception {
        mockMvc.perform(get("/admin/brands"))
                .andExpect(status().isUnauthorized());

        final String sellerToken = tokenFor(registerSellerId());
        mockMvc.perform(get("/admin/brands").header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * error：更新/禁用/启用/删除不存在的品牌 → 404。
     */
    @Test
    @DisplayName("操作不存在品牌返回 404")
    void operatons_onMissingBrandReturn404() throws Exception {
        final String adminToken = adminToken();

        mockMvc.perform(put("/admin/brands/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"耐克\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.brand_not_found"));

        mockMvc.perform(post("/admin/brands/999999/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.brand_not_found"));

        mockMvc.perform(post("/admin/brands/999999/enable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/admin/brands/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    /**
     * error：空名称创建被拒（JSR-380，400 generic.validation_failed）。
     */
    @Test
    @DisplayName("空名称创建返回 400")
    void create_blankNameRejected() throws Exception {
        mockMvc.perform(post("/admin/brands")
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
        mockMvc.perform(get("/admin/brands")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    /**
     * 并发：两个请求同时创建同名品牌——守卫先查后插存在竞态窗口，
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
                post("/admin/brands")
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
        assertThat(brandRepository.count()).isEqualTo(1);
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
     * 创建品牌并返回品牌 ID。
     *
     * @param name 品牌名称
     * @param logo 品牌 logo（可空）
     * @return 品牌 ID
     */
    private long createAs(String name, String logo) throws Exception {
        final String body = logo == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"logo\":\"" + logo + "\"}";
        final MvcResult result = mockMvc.perform(post("/admin/brands")
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