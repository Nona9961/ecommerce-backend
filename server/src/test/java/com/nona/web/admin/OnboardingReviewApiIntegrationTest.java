package com.nona.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.events.Dispatcher;
import com.nona.events.EventHandler;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import com.nona.util.JacksonUtil;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台入驻审核 REST 端点集成测试：待审/全量列表、审核通过/驳回（真实 Security 链 + H2 表）。
 * <p>
 * 平台运营账号无落点（单表无 admin 类型，fail-closed），审核人身份以测试
 * token（uid + ADMIN portal）+ mock 用户上下文（ADMIN 角色）模拟——生产路径
 * 由 RBAC 落点（后续版本接入）承接。覆盖：happy（列表分页、通过、驳回、
 * 驳回重提后再通过）、critical（through 事件发布 payload、并发审核只有一个状态
 * 迁移生效）、error（非待审审核 400、空驳回原因 400、不存在 404、未认证 401、
 * 商家跨端 403）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class OnboardingReviewApiIntegrationTest {

    /**
     * 测试平台运营账号 ID（无库内落点，仅令牌主体）
     */
    private static final long ADMIN_UID = 9001L;

    /**
     * 事件总线（approve 后断言 ApplicationApproved 事件）
     */
    @Autowired
    private Dispatcher dispatcher;

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
     * JWT 签发器（构造商家/平台令牌）
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
     * 事件捕获器（覆盖日志处理器；用例后恢复）
     */
    private EventHandler<ApplicationApprovedEvent.ApplicationApprovedData, Void> eventCapture;

    /**
     * 被捕获的审核通过事件
     */
    private final AtomicReference<ApplicationApprovedEvent> captured = new AtomicReference<>();

    /**
     * 每用例前清空数据；stub 缓存 miss；绑定事件捕获器；平台身份 stub。
     */
    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
        when(authUserCache.get(ADMIN_UID)).thenReturn(java.util.Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("ADMIN"), List.of())));
        captured.set(null);
        eventCapture = new CapturingHandler(captured);
        dispatcher.register(ApplicationApprovedEvent.TYPE, eventCapture);
    }

    /**
     * 用例后恢复日志处理器（同一事件类型只允许一个处理器，避免跨测试类污染）。
     */
    @AfterEach
    void tearDown() {
        dispatcher.register(ApplicationApprovedEvent.TYPE, new SilenceLogHandler());
    }

    /**
     * happy：待审列表分页（状态过滤 + 资料完整可见）。
     */
    @Test
    @DisplayName("待审列表分页且资料可见")
    void list_pendingApplications_paged() throws Exception {
        final String adminToken = adminToken();
        final long firstAccount = registerSellerId();
        final long secondAccount = registerSellerId();
        submitAs(firstAccount, "店铺甲");
        submitAs(secondAccount, "店铺乙");

        mockMvc.perform(get("/admin/onboarding")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.records[0].accountId").value(firstAccount))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.records[1].contactName").value("张三"));
    }

    /**
     * happy：审核通过——状态迁移 approved，审核人/时间落位，发布 ApplicationApproved
     * 事件（payload 含申请 ID / 提交实体 / 店铺名，供开店编排消费）。
     */
    @Test
    @DisplayName("审核通过迁移并发布事件")
    void approve_movesToApprovedAndPublishesEvent() throws Exception {
        final long sellerId = registerSellerId();
        final long applicationId = submitAs(sellerId, "店铺甲");

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus().name())
                .isEqualTo("APPROVED");
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getReviewerId())
                .isEqualTo(ADMIN_UID);
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getReviewTime())
                .isNotNull();

        final ApplicationApprovedEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.getType()).isEqualTo(ApplicationApprovedEvent.TYPE);
        assertThat(event.getPayload().applicationId()).isEqualTo(applicationId);
        assertThat(event.getPayload().accountId()).isEqualTo(sellerId);
        assertThat(event.getPayload().shopName()).isEqualTo("店铺甲");
    }

    /**
     * happy：审核驳回——状态迁移 rejected，驳回原因/审核人/时间落位（不发布通过事件）。
     */
    @Test
    @DisplayName("审核驳回迁移且附原因")
    void reject_movesToRejectedWithReason() throws Exception {
        final long applicationId = submitAs(registerSellerId(), "店铺甲");

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"资料不完整\"}"))
                .andExpect(status().isOk());

        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus().name())
                .isEqualTo("REJECTED");
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getRejectReason())
                .isEqualTo("资料不完整");
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getReviewerId())
                .isEqualTo(ADMIN_UID);
        assertThat(captured.get()).isNull();
    }

    /**
     * happy：驳回 → 商家重提 → 再次审核通过（完整入驻审核闭环）。
     */
    @Test
    @DisplayName("驳回重提后再次审核通过")
    void rejectThenResubmitThenApprove_fullCycle() throws Exception {
        final long sellerId = registerSellerId();
        final String sellerToken = tokenFor(sellerId);
        final long applicationId = submitAs(sellerId, "店铺甲");
        final String adminToken = adminToken();

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"资料不完整\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/seller/onboarding/" + applicationId + "/resubmit")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"修订店铺\",\"contactName\":\"李四\",\"contactPhone\":\"13900139000\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus().name())
                .isEqualTo("APPROVED");
        final ApplicationApprovedEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.getPayload().shopName()).isEqualTo("修订店铺");
    }

    /**
     * error：重复审核（已驳回再驳回）→ 400 状态机守卫。
     */
    @Test
    @DisplayName("重复审核拒绝")
    void approve_nonPending_returns400() throws Exception {
        final long applicationId = submitAs(registerSellerId(), "店铺甲");
        final String adminToken = adminToken();
        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("identity.onboarding_state"));
    }

    /**
     * error：空驳回原因 → 400（驳回必须附原因）。
     */
    @Test
    @DisplayName("空驳回原因拒绝")
    void reject_blankReason_returns400() throws Exception {
        final long applicationId = submitAs(registerSellerId(), "店铺甲");

        mockMvc.perform(post("/admin/onboarding/" + applicationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));

        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus().name())
                .isEqualTo("PENDING");
    }

    /**
     * error：审核不存在的申请 → 404。
     */
    @Test
    @DisplayName("审核不存在申请返回 404")
    void approve_missingApplication_returns404() throws Exception {
        mockMvc.perform(post("/admin/onboarding/999999/approve")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.onboarding_not_found"));
    }

    /**
     * error：未认证 401；商家 token 访问平台接口 → 403。
     */
    @Test
    @DisplayName("未认证 401 且商家跨端 403")
    void auth_unauthorizedAndWrongRole() throws Exception {
        mockMvc.perform(get("/admin/onboarding"))
                .andExpect(status().isUnauthorized());

        final String sellerToken = tokenFor(registerSellerId());
        mockMvc.perform(get("/admin/onboarding").header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    /**
     * 并发：两个审核动作同时作用于同一待审申请——恰有一个状态迁移生效，
     * 另一个被状态机守卫拒绝（申请行写锁串行化）。
     */
    @Test
    @DisplayName("并发审核只有一个生效")
    void concurrentReview_exactlyOneMigrates() throws Exception {
        final long applicationId = submitAs(registerSellerId(), "店铺甲");
        final String adminToken = adminToken();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger rejections = new java.util.concurrent.atomic.AtomicInteger();

        final Runnable approve = () -> invoke(adminToken,
                post("/admin/onboarding/" + applicationId + "/approve"), 200, successes);
        final Runnable reject = () -> invoke(adminToken,
                post("/admin/onboarding/" + applicationId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"资料不完整\"}"),
                200, rejections);
        for (final Runnable action : List.of(approve, reject)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get() + rejections.get()).isEqualTo(1);
        final String status = applicationRepository.findById(applicationId).orElseThrow().getStatus().name();
        assertThat(status).isIn("APPROVED", "REJECTED");
    }

    /**
     * 并发执行单个审核动作并分类计数。
     */
    private void invoke(String token,
                        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                        int expectedStatus,
                        java.util.concurrent.atomic.AtomicInteger counter) {
        try {
            final MvcResult result = mockMvc.perform(builder.header("Authorization", "Bearer " + token)).andReturn();
            if (result.getResponse().getStatus() == expectedStatus) {
                counter.incrementAndGet();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /**
     * 事件捕获处理器（覆盖总线上的日志处理器，断言审核通过事件载荷；
     * EventHandler 为双抽象方法接口，不能以 lambda 实现）。
     */
    private static final class CapturingHandler
            extends com.nona.events.AbstractHandler<ApplicationApprovedEvent.ApplicationApprovedData, Void> {

        /**
         * 捕获结果容器（外部断言读取）
         */
        private final AtomicReference<ApplicationApprovedEvent> captured;

        /**
         * 构造捕获处理器。
         *
         * @param captured 捕获结果容器
         */
        private CapturingHandler(AtomicReference<ApplicationApprovedEvent> captured) {
            this.captured = captured;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Void handle(com.nona.events.Event<ApplicationApprovedEvent.ApplicationApprovedData> event) {
            captured.set((ApplicationApprovedEvent) event);
            return null;
        }
    }

    /**
     * 静默日志处理器（事件兜底处理器恢复形态：验收期事件消费方未注册时
     * 不抛错）。开店职责已由审核用例同事务编排承接，事件仅承载通知语义。
     */
    private static final class SilenceLogHandler
            extends com.nona.events.AbstractHandler<ApplicationApprovedEvent.ApplicationApprovedData, Void> {

        /**
         * {@inheritDoc}
         */
        @Override
        public Void handle(com.nona.events.Event<ApplicationApprovedEvent.ApplicationApprovedData> event) {
            return null;
        }
    }
}