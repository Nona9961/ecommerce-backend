package com.nona.application.admin;

import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.exceptions.BusinessException;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * 入驻审核通过 → 开店跨域同事务编排集成测试（identity → catalog）：
 * 审核通过 = 申请状态迁移 + 店铺创建（数据源自申请资料）+ 账号-店铺关联绑定
 * 同一事务，任一失败整体回滚；审核通过后主动失效该账号的用户上下文缓存
 * （关系变更即时生效路径）。
 * <p>
 * 全部经真实仓储链路（H2 内存库 + 真实 Repository 实现）；申请行以 JPA 直插
 * 准备（聚焦 approve 编排本身，提交/注册链路由既有 web 测试覆盖）；缓存以
 * mock 隔离真实 Redis 并断言失效调用。调用点以 TrackingContext.withScope
 * 绑定跟踪作用域（无请求线程直调用例必须显式绑定；approve 写 global 表，
 * 无需租户）。重复审核/已驳回审核的状态守卫断言为既有契约，防编排破坏回归。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class OnboardingApprovalOrchestrationIntegrationAcTest {

    /**
     * 平台审核人账号 ID（测试主体，无库内落点）
     */
    private static final long REVIEWER_UID = 9001L;

    /**
     * 平台审核用例（被测编排入口）
     */
    @Autowired
    private OnboardingReviewUseCase reviewUseCase;

    /**
     * 申请 JPA 仓储（数据准备与直接断言）
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
     * 用户上下文缓存（mock，隔离 Redis；断言编排主动失效该 uid 缓存）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前清空申请/店铺/关联三表。
     */
    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        shopJpaRepository.deleteAll();
        relJpaRepository.deleteAll();
    }

    /**
     * 用例后无残留状态（无事件处理器注册，无需恢复）。
     */
    @AfterEach
    void tearDown() {
        // 无全局注册物
    }

    /**
     * happy 主流程：审核通过 → 申请迁移 approved 且审核记录落位；同事务创建
     * 店铺（名称取自申请资料；申请无 logo/简介字段 → 店铺可空字段恒空）；
     * 账号-店铺关联首绑；审核通过后主动失效该账号缓存（关系变更即时生效）。
     */
    @Test
    @DisplayName("审核通过后店铺与关联同事务存在且缓存失效")
    void approve_createsShopAndRelInSameTx_withAuditAndCacheEviction() {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺甲");

        TrackingContext.withScope(() -> reviewUseCase.approve(applicationId, REVIEWER_UID));

        // 申请迁移 + 审核记录（审核字段自带）
        final MerchantApplicationPO application = applicationRepository.findById(applicationId).orElseThrow();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application.getReviewerId()).isEqualTo(REVIEWER_UID);
        assertThat(application.getReviewTime()).isNotNull();

        // 店铺存在：名称=申请店铺名；初始状态 NORMAL；申请资料无 logo/简介 → 恒空
        assertThat(shopJpaRepository.count()).isEqualTo(1);
        final ShopPO shop = shopJpaRepository.findAll().get(0);
        assertThat(shop.getName()).isEqualTo("店铺甲");
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.NORMAL);
        assertThat(shop.getLogo()).isNull();
        assertThat(shop.getDescription()).isNull();

        // 账号-店铺关联首绑（同一账户、店铺 ID 指向上一步创建的店铺）
        final List<AccountShopRelPO> rels = relJpaRepository.findByAccountId(sellerId);
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).getShopId()).isEqualTo(shop.getId());

        // 关系变更主动失效该 uid 缓存（下次请求 miss 回填即生效）
        verify(authUserCache).delete(sellerId);
    }

    /**
     * 数据隔离：rel 绑定的是申请所属账号，而非审核人账号（审核人 9001 ≠ 申请账号）。
     */
    @Test
    @DisplayName("关联绑定申请账号而非审核人")
    void approve_bindsRelToApplicantAccount_notReviewer() {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺乙");

        TrackingContext.withScope(() -> reviewUseCase.approve(applicationId, REVIEWER_UID));

        final List<AccountShopRelPO> rels = relJpaRepository.findByAccountId(sellerId);
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).getAccountId()).isEqualTo(sellerId);
        assertThat(rels.get(0).getAccountId()).isNotEqualTo(REVIEWER_UID);
        final List<AccountShopRelPO> reviewerRels = relJpaRepository.findByAccountId(REVIEWER_UID);
        assertThat(reviewerRels).isEmpty();
    }

    /**
     * 幂等（critical）：重复审核同一申请（已通过）→ 状态机守卫拒绝（重复 approve
     * 守卫在聚合，既有契约），且不产生第二个店铺/第二条关联。
     */
    @Test
    @DisplayName("重复审核拒绝且不重复开店")
    void approve_twice_rejectsSecondAndKeepsSingleShop() {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺丙");

        TrackingContext.withScope(() -> reviewUseCase.approve(applicationId, REVIEWER_UID));
        assertThatThrownBy(() -> TrackingContext.withScope(
                () -> reviewUseCase.approve(applicationId, REVIEWER_UID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("identity.onboarding_state");

        assertThat(shopJpaRepository.count()).isEqualTo(1);
        assertThat(relJpaRepository.findByAccountId(sellerId)).hasSize(1);
    }

    /**
     * 并发（critical）：两审核动作同时 approve 同一申请 —— 申请行写锁串行化，
     * 恰好一次状态迁移成功、另一个被守卫拒绝；店铺与关联恒为单个。
     */
    @Test
    @DisplayName("并发审核恰好一个生效且仅开一店")
    void concurrentApprove_exactlyOneShopAndOneRel() throws Exception {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺丁");
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger rejections = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();

        final Runnable approve = () -> {
            ready.countDown();
            try {
                start.await();
                TrackingContext.withScope(() -> reviewUseCase.approve(applicationId, REVIEWER_UID));
                successes.incrementAndGet();
            } catch (BusinessException e) {
                rejections.incrementAndGet();
            } catch (Exception e) {
                unexpected.set(e);
            }
        };
        for (int i = 0; i < 2; i++) {
            executor.submit(approve);
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected.get()).isNull();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(1);
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPROVED);
        assertThat(shopJpaRepository.count()).isEqualTo(1);
        assertThat(relJpaRepository.findByAccountId(sellerId)).hasSize(1);
    }

    /**
     * error：非待审状态审核拒绝（已驳回申请 approve → 400 状态机守卫），
     * 且不产生店铺/关联残留（守卫为既有契约，防编排破坏回归）。
     */
    @Test
    @DisplayName("已驳回申请审核拒绝且无店铺残留")
    void approve_rejectedApplication_failsWithStateGuardAndNoShop() {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺戊");
        final MerchantApplicationPO po = applicationRepository.findById(applicationId).orElseThrow();
        po.setStatus(ApplicationStatus.REJECTED);
        po.setRejectReason("资料不完整");
        applicationRepository.save(po);

        assertThatThrownBy(() -> TrackingContext.withScope(
                () -> reviewUseCase.approve(applicationId, REVIEWER_UID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("identity.onboarding_state");

        assertThat(shopJpaRepository.count()).isZero();
        assertThat(relJpaRepository.findByAccountId(sellerId)).isEmpty();
    }

    /**
     * 准备一条待审申请（JPA 直插；审计时间显式赋值，对齐既有直插形制）。
     *
     * @param accountId 商家账号 ID
     * @param shopName  店铺名
     * @return 申请 ID
     */
    private long createPendingApplication(long accountId, String shopName) {
        final MerchantApplicationPO po = new MerchantApplicationPO();
        po.setId(IDUtils.generateID());
        po.setAccountId(accountId);
        po.setShopName(shopName);
        po.setContactName("张三");
        po.setContactPhone("13800138000");
        po.setStatus(ApplicationStatus.PENDING);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        applicationRepository.save(po);
        return po.getId();
    }

    /**
     * 生成与用例无关的唯一账号 ID（避免与审核人常量碰撞）。
     *
     * @return 账号 ID
     */
    private static long uniqueId() {
        return IDUtils.generateID();
    }
}