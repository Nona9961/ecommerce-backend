package com.nona.application.admin;

import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 入驻审核通过 → 开店编排的失败回滚集成测试（核心豁免面）：
 * 编排同事务中任一工序失败 → 申请状态迁移、店铺创建、关联绑定整体回滚
 * （数据库层面无任何残留，而非仅拒绝后续步骤）。
 * <p>
 * 失败注入点 = 店铺落库（{@link ShopRepository#save} 抛异常，模拟 DB 故障）；
 * 其余链路（申请 JPA、关联 JPA、缓存 mock）真实；调用点以
 * TrackingContext.withScope 绑定跟踪作用域（无请求线程直调用例必须显式
 * 绑定；写 global 表无租户需求）。编排内任一工序失败 → 申请迁移、店铺与
 * 关联整体回滚（数据库层面无任何残留，非仅拒绝后续步骤），缓存亦不失效。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class OnboardingApprovalRollbackIntegrationTest {

    /**
     * 平台审核人账号 ID（测试主体）
     */
    private static final long REVIEWER_UID = 9101L;

    /**
     * 平台审核用例（被测编排入口）
     */
    @Autowired
    private OnboardingReviewUseCase reviewUseCase;

    /**
     * 申请 JPA 仓储（回滚断言）
     */
    @Autowired
    private MerchantApplicationJpaRepository applicationRepository;

    /**
     * 店铺 JPA 仓储（回滚断言）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 账号-店铺关联 JPA 仓储（回滚断言）
     */
    @Autowired
    private AccountShopRelJpaRepository relJpaRepository;

    /**
     * 店铺仓储（mock：注入落库故障，触发编排回滚）
     */
    @MockitoBean
    private ShopRepository shopRepository;

    /**
     * 用户上下文缓存（mock；失败路径不得触发失效调用）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前清空三表并装配店铺落库故障。
     */
    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        shopJpaRepository.deleteAll();
        relJpaRepository.deleteAll();
        doThrow(new IllegalStateException("模拟店铺落库故障"))
                .when(shopRepository).save(any(Shop.class));
    }

    /**
     * error：店铺落库失败 → 审核事务整体回滚——申请停留 pending（状态迁移
     * 撤销）、店铺与关联零残留、缓存未被失效（失败路径无关系变更生效）。
     */
    @Test
    @DisplayName("店铺落库失败则申请/店铺/关联整体回滚")
    void approve_shopSaveFailure_rollsBackAllAndNoResidue() {
        final long sellerId = uniqueId();
        final long applicationId = createPendingApplication(sellerId, "店铺甲");

        assertThatThrownBy(() -> TrackingContext.withScope(
                () -> reviewUseCase.approve(applicationId, REVIEWER_UID)))
                .isInstanceOf(IllegalStateException.class);

        // 状态迁移撤销（含审核记录回滚）——同事务回滚的核心断言
        final MerchantApplicationPO application = applicationRepository.findById(applicationId).orElseThrow();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(application.getReviewerId()).isNull();

        // 店铺与关联零残留（数据库层面无部分提交）
        assertThat(shopJpaRepository.count()).isZero();
        assertThat(relJpaRepository.findByAccountId(sellerId)).isEmpty();

        // 失败路径不触发缓存失效（无效假信号，避免相邻账号上下文被误清）
        verify(authUserCache, never()).delete(anyLong());
    }

    /**
     * 准备一条待审申请（JPA 直插，对齐既有直插形制）。
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
     * 生成与用例无关的唯一账号 ID。
     *
     * @return 账号 ID
     */
    private static long uniqueId() {
        return IDUtils.generateID();
    }
}