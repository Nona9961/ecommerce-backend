package com.nona.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台入驻审核端点 → 开店跨域同事务编排的端到端集成测试（真实 Security 链 +
 * H2 内存库）：POST /admin/onboarding/{applicationId}/approve 一次请求完成
 * 审核通过 + 店铺创建 + 账号-店铺关联绑定；失败路径（状态守卫）不产生任何
 * 店铺/关联残留。
 * <p>
 * 平台运营账号无库内落点（fail-closed），审核人身份以测试 token + mock 用户
 * 上下文模拟（对齐既有 OnboardingReviewApiIntegrationAcTest 形制）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class OnboardingApprovalShopApiIntegrationAcTest {

    /**
     * 测试平台运营账号 ID（无库内落点，仅令牌主体）
     */
    private static final long ADMIN_UID = 9201L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 申请 JPA 仓储（直接断言）
     */
    @Autowired
    private MerchantApplicationJpaRepository applicationRepository;

    /**
     * 店铺 JPA 仓储（开店断言）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 账号-店铺关联 JPA 仓储（绑定断言）
     */
    @Autowired
    private AccountShopRelJpaRepository relJpaRepository;

    /**
     * JWT 签发器
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 每用例前清空三表；stub 缓存 miss 与平台身份。
     */
    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        shopJpaRepository.deleteAll();
        relJpaRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
        when(authUserCache.get(ADMIN_UID)).thenReturn(java.util.Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
    }

    /**
     * happy 端到端：提交申请 → 平台审核通过 → 一次请求内开店 + 绑定关联。
     */
    @Test
    @DisplayName("审核通过端点一次请求完成开店与关联绑定")
    void approve_endToEndCreatesShopAndRel() throws Exception {
        final long sellerId = registerSellerId();
        final long applicationId = submitAs(sellerId, "端到端店铺");

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        final MerchantApplicationPO application = applicationRepository.findById(applicationId).orElseThrow();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        assertThat(shopJpaRepository.count()).isEqualTo(1);
        final var shop = shopJpaRepository.findAll().get(0);
        assertThat(shop.getName()).isEqualTo("端到端店铺");

        final List<AccountShopRelPO> rels = relJpaRepository.findByAccountId(sellerId);
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).getShopId()).isEqualTo(shop.getId());
    }

    /**
     * error 端到端：已驳回申请再审核通过 → 400 状态机守卫，且不产生店铺/关联
     * 残留（失败请求零副作用）。
     */
    @Test
    @DisplayName("已驳回申请审核通过返回 400 且无店铺残留")
    void approve_rejectedEndToEnd_returns400AndNoShop() throws Exception {
        final long sellerId = registerSellerId();
        final long applicationId = submitAs(sellerId, "不应开店的店铺");

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"资料不完整\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("identity.onboarding_state"));

        assertThat(shopJpaRepository.count()).isZero();
        assertThat(relJpaRepository.findByAccountId(sellerId)).isEmpty();
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

    /**
     * 以指定账号提交申请并返回申请 ID。
     *
     * @param accountId 商家账号 ID
     * @param shopName  店铺名
     * @return 申请 ID
     */
    private long submitAs(long accountId, String shopName) throws Exception {
        final MvcResult result = mockMvc.perform(post("/seller/onboarding")
                        .header("Authorization", "Bearer " + tokenFor(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"" + shopName + "\",\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }
}