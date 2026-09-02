package com.nona.web.seller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.factory.MerchantApplicationFactory;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.security.AuthUserCache;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家入驻申请 REST 端点集成测试：提交 / 查看 / 编辑 / 重提（真实 Security 链 + H2 表）。
 * <p>
 * 无真实 Redis（用户上下文缓存以 mock 替代）；商家身份经注册落库 + JWT 签发获得。
 * 覆盖：happy（提交进入待审、查看状态、编辑资料、驳回后重提）、critical（重提清空
 * 旧驳回结论、并发提交 one-pending 收敛）、error（重复提交 400、跨账号归属 404、
 * 非驳回重提 400、终态编辑 400、未认证 401、买家跨端 403）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class OnboardingApiIntegrationTest {

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 申请 JPA 仓储（测试数据清理与直接断言）
     */
    @Autowired
    private MerchantApplicationJpaRepository applicationRepository;

    /**
     * 申请域仓储（造审核前置状态）
     */
    @Autowired
    private MerchantApplicationRepository merchantApplicationRepository;

    /**
     * 申请工厂（造审核前置状态）
     */
    @Autowired
    private MerchantApplicationFactory applicationFactory;

    /**
     * JWT 签发器（构造商家访问令牌）
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
     * 每用例前清空数据并 stub 缓存 miss（过滤器走真实账号状态回填）。
     */
    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * happy：提交申请成功——返回分配的 ID、资料回显与状态 pending。
     */
    @Test
    @DisplayName("提交申请进入待审")
    void submit_createsPendingApplication() throws Exception {
        final String token = registerSeller();

        mockMvc.perform(post("/seller/onboarding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("示例店铺", "张三", "13800138000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.shopName").value("示例店铺"))
                .andExpect(jsonPath("$.data.contactName").value("张三"))
                .andExpect(jsonPath("$.data.contactPhone").value("13800138000"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.rejectReason").doesNotExist());
    }

    /**
     * happy：查看我的申请——状态可见（提交后为待审）。
     */
    @Test
    @DisplayName("查看申请状态为待审")
    void getMyApplication_returnsPending() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");
        final long applicationId = applicationIdOf(token);

        mockMvc.perform(get("/seller/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /**
     * happy：未提交时查看返回 data=null（200，前端展示「未提交」）。
     */
    @Test
    @DisplayName("未提交查看返回空")
    void getMyApplication_withoutSubmission_returnsNull() throws Exception {
        final String token = registerSeller();

        mockMvc.perform(get("/seller/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * happy：编辑资料——字段更新、状态保持待审。
     */
    @Test
    @DisplayName("编辑申请资料成功")
    void update_changesMaterials() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");
        final long applicationId = applicationIdOf(token);

        mockMvc.perform(put("/seller/onboarding/" + applicationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("改后店铺", "李四", "13900139000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopName").value("改后店铺"))
                .andExpect(jsonPath("$.data.contactName").value("李四"))
                .andExpect(jsonPath("$.data.contactPhone").value("13900139000"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /**
     * happy：驳回状态可见原因；编辑后重提——回到待审、原因清空（全链路）。
     */
    @Test
    @DisplayName("驳回可见原因并可修改重提")
    void rejected_viewEditResubmit_cycle() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");
        final long applicationId = applicationIdOf(token);
        rejectForTesting(applicationId, "资料不完整");

        mockMvc.perform(get("/seller/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("资料不完整"));

        mockMvc.perform(put("/seller/onboarding/" + applicationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("修订店铺", "李四", "13900139000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectReason").value("资料不完整"));

        mockMvc.perform(post("/seller/onboarding/" + applicationId + "/resubmit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("修订店铺", "李四", "13900139000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.rejectReason").doesNotExist());
    }

    /**
     * error：已有申请（待审中）再次提交拒绝——one pending per entity。
     */
    @Test
    @DisplayName("重复提交拒绝")
    void submit_whenExisting_rejects() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");

        mockMvc.perform(post("/seller/onboarding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("第二家店", "王五", "13700137000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("identity.onboarding_conflict"));
        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    /**
     * error：跨账号归属隔离——商家 B 操作商家 A 的申请一律 404（不泄露归属）。
     */
    @Test
    @DisplayName("跨账号操作他人申请返回 404")
    void crossAccount_operationsOnForeignApplication_returns404() throws Exception {
        final String ownerToken = registerSeller();
        submitApplication(ownerToken, "店铺甲");
        final long applicationId = applicationIdOf(ownerToken);
        final String strangerToken = registerSeller();

        mockMvc.perform(put("/seller/onboarding/" + applicationId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("抢占", "路人", "13700137000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.onboarding_not_found"));

        mockMvc.perform(post("/seller/onboarding/" + applicationId + "/resubmit")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("抢占", "路人", "13700137000")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/seller/onboarding").header("Authorization", "Bearer " + strangerToken))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * error：pending 状态重提拒绝（重提必须基于被驳回版本）。
     */
    @Test
    @DisplayName("待审状态重提拒绝")
    void resubmit_pending_rejects() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");
        final long applicationId = applicationIdOf(token);

        mockMvc.perform(post("/seller/onboarding/" + applicationId + "/resubmit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("修订店铺", "李四", "13900139000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("identity.onboarding_state"));
    }

    /**
     * error：approved 终态拒绝编辑（不可变）。
     */
    @Test
    @DisplayName("已通过申请编辑拒绝")
    void update_approved_rejects() throws Exception {
        final String token = registerSeller();
        submitApplication(token, "示例店铺");
        final long applicationId = applicationIdOf(token);
        approveForTesting(applicationId);

        mockMvc.perform(put("/seller/onboarding/" + applicationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("改后店铺", "李四", "13900139000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("identity.onboarding_state"));
    }

    /**
     * error：缺必填资料 → 400 参数校验失败。
     */
    @Test
    @DisplayName("必填字段缺失返回 400")
    void submit_missingFields_returnsValidationError() throws Exception {
        final String token = registerSeller();

        mockMvc.perform(post("/seller/onboarding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shopName":"","contactName":"张三","contactPhone":"13800138000"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    /**
     * error：未认证访问 → 401；买家 token 访问商家接口 → 403。
     */
    @Test
    @DisplayName("未认证 401 且买家跨端 403")
    void auth_unauthorizedAndWrongRole() throws Exception {
        mockMvc.perform(get("/seller/onboarding"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));

        final String buyerToken = registerBuyer();
        mockMvc.perform(get("/seller/onboarding").header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * 并发：同一账号同时提交两个申请——恰好一个成功（账号写锁串行化
     * + 唯一约束兜底，one-pending 在并发窗口下收敛）。
     */
    @Test
    @DisplayName("并发提交只一个成功")
    void concurrentSubmit_exactlyOneSucceeds() throws Exception {
        final long sellerId = registerSellerId();
        final String token = tokenFor(sellerId);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final String shopName = "并发店铺" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    final MvcResult result = mockMvc.perform(post("/seller/onboarding")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(validBody(shopName, "张三", "13800138000")))
                            .andReturn();
                    if (result.getResponse().getStatus() == 200) {
                        successes.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 400) {
                        conflicts.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(applicationRepository.count()).isEqualTo(1);
        assertThat(applicationRepository.findByAccountId(sellerId)).isPresent();
    }

    /**
     * 注册商家（落库）并返回其账号 ID。
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
     * 注册商家并返回其访问令牌（JWT；缓存 miss 后真实账号状态回填）。
     *
     * @return JWT
     */
    private String registerSeller() throws Exception {
        return tokenFor(registerSellerId());
    }

    /**
     * 为账号签发访问令牌。
     *
     * @param accountId 账号 ID
     * @return JWT
     */
    private String tokenFor(long accountId) {
        return tokenProvider.issueToken(accountId, Portal.SELLER);
    }

    /**
     * 注册买家并返回其访问令牌。
     *
     * @return JWT
     */
    private String registerBuyer() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return tokenProvider.issueToken(Long.parseLong(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("data").path("userId").asText()),
                Portal.MALL);
    }

    /**
     * 通过接口提交申请（前置步骤）。
     *
     * @param token    商家令牌
     * @param shopName 店铺名
     */
    private void submitApplication(String token, String shopName) throws Exception {
        mockMvc.perform(post("/seller/onboarding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(shopName, "张三", "13800138000")))
                .andExpect(status().isOk());
    }

    /**
     * 读取当前申请 ID（查看接口回读）。
     *
     * @param token 商家令牌
     * @return 申请 ID
     */
    private long applicationIdOf(String token) throws Exception {
        final MvcResult result = mockMvc.perform(get("/seller/onboarding")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    /**
     * 直接落库构造已驳回申请（审核前置状态；审核接口自身在平台审核测试覆盖）。
     *
     * @param applicationId 申请 ID
     * @param reason        驳回原因
     */
    private void rejectForTesting(long applicationId, String reason) {
        final MerchantApplication application = merchantApplicationRepository.getByID(applicationId);
        application.reject(9001L, reason);
        merchantApplicationRepository.save(application);
    }

    /**
     * 直接落库构造已通过申请（审核前置状态；审核接口自身在平台审核测试覆盖）。
     *
     * @param applicationId 申请 ID
     */
    private void approveForTesting(long applicationId) {
        final MerchantApplication application = merchantApplicationRepository.getByID(applicationId);
        application.approve(9001L);
        merchantApplicationRepository.save(application);
    }

    /**
     * 构造合法申请请求体。
     *
     * @param shopName     店铺名
     * @param contactName  联系人
     * @param contactPhone 联系方式
     * @return 请求体 JSON
     */
    private static String validBody(String shopName, String contactName, String contactPhone) {
        return "{\"shopName\":\"" + shopName + "\",\"contactName\":\"" + contactName
                + "\",\"contactPhone\":\"" + contactPhone + "\"}";
    }
}