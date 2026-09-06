package com.nona.application.admin;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.replica.LastWriteMarker;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.context.TrackingContext;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 入驻审核写后埋点契约测试（写用例完成时标记账号，搜索入口据此 3s
 * 主库重路由——TD-08）。
 * <p>
 * 契约：审核通过（商家侧数据生效：申请迁移/开店/绑定同事务）成功后
 * 必须调用 {@link LastWriteMarker#markWrite(Long)} 标记<b>商家账号</b>
 * （数据变更直接归属方；主体语义见设计决策）——商家在
 * 审核通过后 3s 内搜索立即看到自身数据生效（read-your-writes）。
 * <p>
 * 全部经真实仓储链路（H2 + 真实 Repository，fixture 同
 * OnboardingApprovalOrchestrationIntegrationTest）；缓存与埋点以 mock
 * 隔离真实 Redis。红阶段：approve 埋点待落实（markWrite 未被调用），
 * 失败原因 = 实现缺失（埋点缺失）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class OnboardingApprovalWriteMarkerContractTest {

    /**
     * 平台审核人账号 ID（写操作执行者，测试主体）
     */
    private static final long REVIEWER_UID = 9001L;

    /**
     * 入驻商家账号 ID（数据变更归属方、搜索调用者）
     */
    private static final long SELLER_UID = 88001L;

    /**
     * 平台审核用例（被测编排入口）
     */
    @Autowired
    private OnboardingReviewUseCase reviewUseCase;

    /**
     * 申请 JPA 仓储（数据准备）
     */
    @Autowired
    private MerchantApplicationJpaRepository applicationRepository;

    /**
     * 店铺 JPA 仓储（清理）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 账号-店铺关联 JPA 仓储（清理）
     */
    @Autowired
    private AccountShopRelJpaRepository relJpaRepository;

    /**
     * 写后窗口埋点（mock，断言审核通过后商家账号被标记）
     */
    @MockitoBean
    private LastWriteMarker lastWriteMarker;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
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
     * happy：审核通过 → 标记商家账号写后窗口（markWrite(商家 uid)）。
     */
    @Test
    @DisplayName("审核通过后标记商家账号写后窗口")
    void approve_marksMerchantWriteWindow() {
        final long applicationId = createPendingApplication(SELLER_UID, "店铺甲");

        TrackingContext.withScope(() -> reviewUseCase.approve(applicationId, REVIEWER_UID));

        // 埋点契约：审批通过 = 商家侧数据生效，商家 3s 内搜索立即可见
        verify(lastWriteMarker).markWrite(SELLER_UID);
    }

    /**
     * 直插待审申请（聚焦 approve 编排，提交/注册链路由既有测试覆盖）。
     *
     * @param accountId 申请商家账号
     * @param shopName  申请店铺名
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
        assertThat(po.getId()).isNotNull();
        return po.getId();
    }
}