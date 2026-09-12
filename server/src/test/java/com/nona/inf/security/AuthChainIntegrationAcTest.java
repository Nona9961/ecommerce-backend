package com.nona.inf.security;

import com.nona.api.auth.Portal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路集成测试：JWT 过滤器 + Spring Security 路由 + 用户上下文缓存/DB 回填。
 * <p>
 * 无真实 Redis（缓存以 mock 替代）、无真实账号表（DB SPI 以内存实现替代），
 * 覆盖：缓存命中组装跟踪作用域、miss 走 DB 回填、封禁 403、
 * 角色路由 401/403、token 过期/篡改 401、公开路径放行。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class AuthChainIntegrationAcTest {

    /**
     * 买家 uid（缓存命中场景专用，与 DB 场景错开避免状态耦合）
     */
    private static final long CACHED_BUYER_UID = 1001L;

    /**
     * 经 DB 回填的买家 uid
     */
    private static final long DB_BUYER_UID = 2001L;

    /**
     * 被封禁用户的 uid
     */
    private static final long BANNED_UID = 3001L;

    /**
     * 商家 uid
     */
    private static final long SELLER_UID = 4001L;

    /**
     * 平台运营 uid
     */
    private static final long ADMIN_UID = 5001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * JWT 签发器（测试直接签发合法 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * DB SPI（内存实现）
     */
    @Autowired
    private AccountStatusProvider accountStatusProvider;

    /**
     * 安全配置（过期 token 构造需要同一密钥）
     */
    @Autowired
    private SecurityProperties securityProperties;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 测试配置：注册内存版 DB SPI（@Primary 覆盖主实现的注入优先级，不依赖 bean 覆盖机制）。
     */
    @TestConfiguration
    static class AuthChainTestConfig {

        /**
         * 内存版账号状态提供器。
         *
         * @return DB SPI 的测试实现
         */
        @Bean
        @Primary
        AccountStatusProvider accountStatusProvider() {
            return new InMemoryAccountStatusProvider();
        }
    }

    /**
     * 每用例前重置 mock 行为，并按需注册 DB 用户；测试不依赖真实 Redis，故禁用 Redis 健康检查组件。
     */
    @BeforeEach
    void setUp() {
        final InMemoryAccountStatusProvider provider = (InMemoryAccountStatusProvider) accountStatusProvider;
        provider.reset();
        provider.register(DB_BUYER_UID, active(List.of("BUYER")));
        provider.register(BANNED_UID, new AuthUserContext(AccountStatus.BANNED, List.of("BUYER"), List.of()));
        provider.register(SELLER_UID, active(List.of("SELLER")));
        provider.register(ADMIN_UID, active(List.of("ADMIN")));
    }

    /**
     * 无 token 访问受保护端点 → 401（未认证）。
     */
    @Test
    void noToken_onProtectedRoute_returns401() throws Exception {
        mockMvc.perform(get("/mall/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
        mockMvc.perform(get("/seller/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 缓存命中：直接组装跟踪作用域，不再查询 DB，也不回填缓存。
     */
    @Test
    void cacheHit_assemblesTrackingScope_withoutDbQuery() throws Exception {
        when(authUserCache.get(CACHED_BUYER_UID)).thenReturn(Optional.of(active(List.of("BUYER"))));
        final String token = tokenProvider.issueToken(CACHED_BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("1001"))
                .andExpect(jsonPath("$.roles[0]").value("BUYER"))
                .andExpect(jsonPath("$.tenantID").value((String) null));

        assertThat(((InMemoryAccountStatusProvider) accountStatusProvider).loadCount()).isZero();
        verify(authUserCache, never()).put(eq(CACHED_BUYER_UID), any());
    }

    /**
     * 缓存 miss：走 DB（SPI）查询并回填缓存，跟踪作用域正常组装。
     */
    @Test
    void cacheMiss_loadsDbAndBackfillsCache() throws Exception {
        when(authUserCache.get(DB_BUYER_UID)).thenReturn(Optional.empty());
        final String token = tokenProvider.issueToken(DB_BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("2001"))
                .andExpect(jsonPath("$.roles[0]").value("BUYER"));

        assertThat(((InMemoryAccountStatusProvider) accountStatusProvider).loadCount()).isEqualTo(1);
        verify(authUserCache).put(eq(DB_BUYER_UID), any(AuthUserContext.class));
    }

    /**
     * 缓存 miss 且 DB 无此用户 → 401（fail-closed：查无账号视为未认证）。
     */
    @Test
    void cacheMissAndUserAbsent_returns401() throws Exception {
        when(authUserCache.get(9999L)).thenReturn(Optional.empty());
        final String token = tokenProvider.issueToken(9999L, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 缓存返回封禁状态 → 403（统一 auth.forbidden），封禁即时生效。
     */
    @Test
    void bannedFromCache_returns403() throws Exception {
        when(authUserCache.get(BANNED_UID)).thenReturn(Optional.of(new AuthUserContext(AccountStatus.BANNED, List.of("BUYER"), List.of())));
        final String token = tokenProvider.issueToken(BANNED_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * 缓存 miss 后 DB 返回封禁（平台封禁删缓存后的下一请求）→ 403 并回填封禁态。
     */
    @Test
    void bannedFromDb_returns403AndBackfills() throws Exception {
        when(authUserCache.get(BANNED_UID)).thenReturn(Optional.empty());
        final String token = tokenProvider.issueToken(BANNED_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));

        verify(authUserCache).put(eq(BANNED_UID), any(AuthUserContext.class));
    }

    /**
     * 角色路由：买家 token 访问商家端 → 403（已认证但角色不匹配）。
     */
    @Test
    void buyerTokenOnSellerRoute_returns403() throws Exception {
        when(authUserCache.get(CACHED_BUYER_UID)).thenReturn(Optional.of(active(List.of("BUYER"))));
        final String token = tokenProvider.issueToken(CACHED_BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/seller/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * 角色路由：商家 token 访问商家端 → 200。
     */
    @Test
    void sellerTokenOnSellerRoute_returns200() throws Exception {
        when(authUserCache.get(SELLER_UID)).thenReturn(Optional.of(active(List.of("SELLER"))));
        final String token = tokenProvider.issueToken(SELLER_UID, Portal.SELLER);

        mockMvc.perform(get("/seller/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("4001"))
                .andExpect(jsonPath("$.roles[0]").value("SELLER"));
    }

    /**
     * 角色路由：平台 token 访问平台端 → 200。
     */
    @Test
    void adminTokenOnAdminRoute_returns200() throws Exception {
        when(authUserCache.get(ADMIN_UID)).thenReturn(Optional.of(active(List.of("ADMIN"))));
        final String token = tokenProvider.issueToken(ADMIN_UID, Portal.ADMIN);

        mockMvc.perform(get("/admin/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("5001"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    /**
     * 过期 token → 401。
     */
    @Test
    void expiredToken_returns401() throws Exception {
        when(authUserCache.get(CACHED_BUYER_UID)).thenReturn(Optional.of(active(List.of("BUYER"))));
        final String token = JwtTestTokens.expired(securityProperties, CACHED_BUYER_UID);
        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 篡改 token → 401。
     */
    @Test
    void tamperedToken_returns401() throws Exception {
        final String valid = tokenProvider.issueToken(CACHED_BUYER_UID, Portal.MALL);
        final String tampered = JwtTestTokens.tamperPayload(valid);
        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 公开路径：健康检查无 token 直接放行。
     */
    @Test
    void healthCheck_withoutToken_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * 公开路径：登录/注册端点无 token 不被认证拦截（端点已落地：空 body 触发请求体解析
     * 失败 → 400 + generic.validation_failed，而非 401/403 认证拦截）。
     */
    @Test
    void loginRegistration_withoutToken_notBlockedByAuth() throws Exception {
        mockMvc.perform(post("/auth/login"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/auth/register"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 构造活跃用户上下文（无店铺关联）。
     *
     * @param roles 角色名列表
     * @return 活跃上下文
     */
    private static AuthUserContext active(List<String> roles) {
        return new AuthUserContext(AccountStatus.ACTIVE, roles, List.of());
    }
}