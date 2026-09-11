package com.nona.inf.security;

import com.nona.api.auth.Portal;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路契约测试（真实 JPA 实现）：过滤器 + Spring Security 路由 + 单表 account 真实数据回填。
 * <p>
 * 无真实 Redis（缓存以 mock 替代）；账号数据直接写入 H2 内存库（account / account_shop_rel），
 * AccountStatusProvider 走真实 JPA 实现（回填路径与缓存命中路径均已
 * 接线）。
 * 覆盖：DB 回填组装上下文、商家店铺上下文写入租户（shopIds 落租户）、BANNED 真实数据 403、
 * 缓存命中直取、uid 不存在 401、平台运营（portal=ADMIN）无落点 401（fail-closed）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class AuthChainJpaIntegrationAcTest {

    /**
     * 买家 uid（DB 回填场景）
     */
    private static final long BUYER_UID = 61001L;

    /**
     * 被封禁买家 uid（真实数据 BANNED）
     */
    private static final long BANNED_UID = 61002L;

    /**
     * 商家 uid（DB 回填 + rel 店铺上下文）
     */
    private static final long SELLER_UID = 62001L;

    /**
     * 关联店铺 ID（商家场景）
     */
    private static final long SHOP_ID = 63001L;

    /**
     * 平台运营视角 uid（portal=ADMIN）：单表无 admin 落点，DB 回填查无账号 → 401
     */
    private static final long ADMIN_UID = 64001L;

    /**
     * 不存在的 uid
     */
    private static final long ABSENT_UID = 69999L;

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
     * 账号仓储（单表，测试数据写入）
     */
    @Autowired
    private AccountJpaRepository accountRepository;

    /**
     * 账号-店铺关联仓储（测试数据写入）
     */
    @Autowired
    private AccountShopRelJpaRepository accountShopRelRepository;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前清空账号表并 stub 缓存 miss（走真实 JPA 回填）。
     */
    @BeforeEach
    void setUp() {
        accountShopRelRepository.deleteAll();
        accountRepository.deleteAll();
        when(authUserCache.get(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
    }

    /**
     * DB 回填（买家真实数据）：miss → JPA 查单表 account → 组装跟踪作用域并回填缓存。
     */
    @Test
    void cacheMiss_buyerFromDb_assemblesContextAndBackfills() throws Exception {
        accountRepository.save(newAccountPo(BUYER_UID, "buyer-alice", AccountStatus.NORMAL, AccountType.BUYER));
        final String token = tokenProvider.issueToken(BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("61001"))
                .andExpect(jsonPath("$.roles[0]").value("BUYER"))
                .andExpect(jsonPath("$.tenantID").value((String) null));

        verify(authUserCache).put(eq(BUYER_UID), any(AuthUserContext.class));
    }

    /**
     * DB 回填（商家真实数据 + rel）：account 表命中 type=SELLER → SELLER 角色 + 店铺上下文
     * 写入 跟踪作用域 tenantID（shopIds 落租户）。
     */
    @Test
    void cacheMiss_sellerFromDb_fillsTenantFromShopIds() throws Exception {
        accountRepository.save(newAccountPo(SELLER_UID, "seller-bob", AccountStatus.NORMAL, AccountType.SELLER));
        accountShopRelRepository.save(newRelPo(65001L, SELLER_UID, SHOP_ID));
        final String token = tokenProvider.issueToken(SELLER_UID, Portal.SELLER);

        mockMvc.perform(get("/seller/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("62001"))
                .andExpect(jsonPath("$.roles[0]").value("SELLER"))
                .andExpect(jsonPath("$.tenantID").value("63001"));
    }

    /**
     * BANNED 真实数据：DB 回填返回封禁 → 403（统一 auth.forbidden）。
     */
    @Test
    void cacheMiss_bannedFromDb_returns403() throws Exception {
        accountRepository.save(newAccountPo(BANNED_UID, "banned-buyer", AccountStatus.BANNED, AccountType.BUYER));
        final String token = tokenProvider.issueToken(BANNED_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * 缓存命中（带 shopIds）：不查 DB，过滤器直接取上下文当前店铺写入租户。
     * 随过滤器改造转绿（验证店铺上下文填充机制）。
     */
    @Test
    void cacheHit_withShopIds_fillsTenantFromContext() throws Exception {
        when(authUserCache.get(SELLER_UID)).thenReturn(Optional.of(
                new AuthUserContext(com.nona.inf.security.AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_ID))));
        final String token = tokenProvider.issueToken(SELLER_UID, Portal.SELLER);

        mockMvc.perform(get("/seller/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("62001"))
                .andExpect(jsonPath("$.roles[0]").value("SELLER"))
                .andExpect(jsonPath("$.tenantID").value("63001"));
    }

    /**
     * 平台运营（portal=ADMIN）：单表无 admin 账号落点（RBAC 扩展位），
     * DB 回填查无账号 → 401（fail-closed：admin 端在 RBAC 落地前不可达）。
     */
    @Test
    void cacheMiss_adminUid_returns401() throws Exception {
        final String token = tokenProvider.issueToken(ADMIN_UID, Portal.ADMIN);

        mockMvc.perform(get("/admin/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 单表无此 uid：fail-closed 按未认证处理 → 401。
     */
    @Test
    void cacheMiss_absentUid_returns401() throws Exception {
        final String token = tokenProvider.issueToken(ABSENT_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * 构造账号 PO（单表行）。
     *
     * @param id       账号 ID
     * @param username 用户名
     * @param status   状态
     * @param type     账号类型
     * @return PO
     */
    private static AccountPO newAccountPo(Long id, String username, AccountStatus status, AccountType type) {
        final AccountPO po = new AccountPO();
        po.setId(id);
        po.setUsername(username);
        po.setPasswordHash("$2a$10$test-hash-not-valid");
        po.setStatus(status);
        po.setType(type);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }

    /**
     * 构造关联 PO。
     *
     * @param id        关联 ID
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     * @return PO
     */
    private static AccountShopRelPO newRelPo(Long id, Long accountId, Long shopId) {
        final AccountShopRelPO po = new AccountShopRelPO();
        po.setId(id);
        po.setAccountId(accountId);
        po.setShopId(shopId);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }
}
