package com.nona.web;

import com.nona.api.auth.Portal;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证 REST 端点集成测试：注册 / 登录 / 登出业务场景（真实 Security 链 + H2 单表 account）。
 * <p>
 * 无真实 Redis（用户上下文缓存以 mock 替代）；用例层为 Phase 1 骨架
 * （AuthUseCase 抛 UnsupportedOperationException），全部业务场景预期红。
 * 覆盖：happy（注册→登录→上下文组装）、critical（同 type 重复用户名、错误凭证、
 * 空 shopIds 登录、缓存 miss 回填）、error（BANNED 登录 403、type 定向查询不命中、
 * 跨 type 同名 username 不冲突、ADMIN 注册拒绝）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 账号仓储（单表 account，测试数据写入）
     */
    @Autowired
    private AccountJpaRepository accountRepository;

    /**
     * JWT 签发器（登出场景构造合法 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前清空账号表并 stub 缓存 miss。
     */
    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * happy：买家注册成功，返回新建账号 ID（Phase 1 红：用例骨架）。
     */
    @Test
    void register_buyer_returnsUserId() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").isNumber());
    }

    /**
     * happy：商家注册成功（仅创建账号，开店走入驻审核）。
     */
    @Test
    void register_seller_returnsUserId() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shop-owner\",\"password\":\"secret123\",\"portal\":\"SELLER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").isNumber());
    }

    /**
     * critical：同 type 重复用户名注册 → 业务拒绝（(type, username) 联合唯一）。
     */
    @Test
    void register_duplicateUsername_rejects() throws Exception {
        accountRepository.save(newAccountPo(71001L, "alice", AccountStatus.NORMAL, AccountType.BUYER));
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.username_conflict"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("用户名")));
    }

    /**
     * error：平台账号不接受注册（平台员工由平台创建；单表无 admin 落点，fail-closed）。
     */
    @Test
    void register_adminPortal_rejects() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-admin\",\"password\":\"secret123\",\"portal\":\"ADMIN\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("平台")));
    }

    /**
     * critical：跨 type 同名 username 不冲突（买家与商家各占 (type, username) 联合
     * 唯一的一个槽位，注册均成功）。
     */
    @Test
    void register_sameUsernameAcrossTypes_bothSucceed() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"SELLER\"}"))
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * happy：登录成功返回 token 与店铺 ID 列表（买家端空列表合法）。
     */
    @Test
    void login_buyer_returnsTokenAndEmptyShopIds() throws Exception {
        accountRepository.save(newAccountPo(71002L, "alice", AccountStatus.NORMAL, AccountType.BUYER));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.shopIds").isArray());
    }

    /**
     * error：凭证错误（密码不匹配）→ 登录失败，不泄露账号存在性。
     */
    @Test
    void login_wrongPassword_fails() throws Exception {
        accountRepository.save(newAccountPo(71003L, "alice", AccountStatus.NORMAL, AccountType.BUYER));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-pass\",\"portal\":\"MALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.bad_credentials"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("用户名或密码")));
    }

    /**
     * error：BANNED 账号登录 → 403（封禁账号拒绝登录）。
     */
    @Test
    void login_bannedAccount_returns403() throws Exception {
        accountRepository.save(newAccountPo(71004L, "banned-buyer", AccountStatus.BANNED, AccountType.BUYER));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"banned-buyer\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * error：type 定向查询不命中（用户名在商家命名空间，却以买家门户登录；
     * 单表按 (BUYER, alice) 查不到）→ 登录失败（fail-closed）。
     */
    @Test
    void login_wrongType_miss_fails() throws Exception {
        accountRepository.save(newAccountPo(72001L, "alice", AccountStatus.NORMAL, AccountType.SELLER));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.bad_credentials"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("用户名或密码")));
    }

    /**
     * happy：登出幂等成功（无状态语义收口；需已认证）。
     */
    @Test
    void logout_authenticated_returnsSuccess() throws Exception {
        final String token = tokenProvider.issueToken(71005L, Portal.MALL);
        when(authUserCache.get(71005L)).thenReturn(java.util.Optional.of(
                new com.nona.inf.security.AuthUserContext(
                        com.nona.inf.security.AccountStatus.ACTIVE, java.util.List.of("BUYER"), java.util.List.of())));

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * error：未认证登出 → 401（登出路径受保护）。
     */
    @Test
    void logout_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error：登录请求缺 portal 字段 → 参数校验失败（HTTP 400 + generic.validation_failed，
     * 非 401/403，公开路径语义）。
     */
    @Test
    void login_missingPortal_rejectsByValidation() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", not(containsString("unauthorized"))));
    }

    /**
     * 构造账号 PO（单表行）：密码摘要为合法 BCrypt（对明文 secret123，
     * 登录用例的真实凭证校验需要可匹配的摘要）。
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
        po.setPasswordHash("$2a$10$tfpehLbffqJ2obdrC.Qg2ekghXdEiLWxC8aS/RZ3qDEkXM4pHcceK");
        po.setStatus(status);
        po.setType(type);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }
}
