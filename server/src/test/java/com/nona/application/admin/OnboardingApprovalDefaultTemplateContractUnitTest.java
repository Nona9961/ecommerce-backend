package com.nona.application.admin;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.domain.catalog.factory.FreightTemplateFactory;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.catalog.factory.ShopFactory;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.repo.AccountShopRelRepository;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.events.Dispatcher;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.replica.LastWriteMarker;
import com.nona.inf.security.AuthUserCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开店编排补建默认运费模板契约测试（开店即建默认模板，
 * 商品未绑定回退锚点恒存在）：审核通过链路（approve）在创建店铺的同
 * 一事务内自动创建并落库店铺默认运费模板（tenant=shopId，与普通模板
 * 同构）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingApprovalDefaultTemplateContractUnitTest {

    /**
     * 提权事务模板直通（契约测试装配）：elevatedInTransaction 在真实链路
     * 承担事务与提权作用域，契约测试以直通回调代替——编排调用面验证
     * 聚焦，事务/提权语义由既有集成测试以真实链路覆盖。
     *
     * @param invocation mock 调用点
     * @return 回调执行结果
     * @throws Exception 回调异常透传
     */
    private static Object passthroughElevatedCallback(InvocationOnMock invocation) throws Exception {
        final Callable<?> callable = invocation.getArgument(1);
        return callable.call();
    }

    /**
     * 测试申请 ID（mock 聚合，无库内落点）
     */
    private static final long APPLICATION_ID = 90001L;

    /**
     * 平台审核人账号 ID
     */
    private static final long REVIEWER_ID = 9002L;

    /**
     * 申请商家账号 ID
     */
    private static final long ACCOUNT_ID = 9003L;

    /**
     * 开店后店铺 ID（店铺工厂产出）
     */
    private static final long SHOP_ID = 70001L;

    /* ---------- 被测装配（全部 mock：契约测试关注编排调用面） ---------- */

    @Mock
    private MerchantApplicationRepository applicationRepository;

    @Mock
    private ShopFactory shopFactory;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private FreightTemplateFactory freightTemplateFactory;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private AccountShopRelRepository accountShopRelRepository;

    @Mock
    private AuthUserCache authUserCache;

    @Mock
    private Dispatcher dispatcher;

    @Mock
    private LastWriteMarker lastWriteMarker;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OnboardingReviewUseCase reviewUseCase;

    /**
     * 装配用例与申请/店铺桩：申请待审、店铺名取自申请资料、默认模板工厂
     * 产出就位、提权事务模板直通回调（elevatedInTransaction 直接执行
     * 编排主体——契约测试聚焦编排调用面，事务/提权语义由既有集成测试
     * 以真实链路覆盖）。
     */
    @BeforeEach
    void setUp() throws Exception {
        reviewUseCase = new OnboardingReviewUseCase(
                applicationRepository, shopFactory, shopRepository,
                freightTemplateFactory, freightTemplateRepository,
                accountShopRelRepository, authUserCache, dispatcher, lastWriteMarker,
                tenantPrivilege, transactionTemplate);
        when(tenantPrivilege.elevatedInTransaction(any(TransactionTemplate.class), any()))
                .thenAnswer(OnboardingApprovalDefaultTemplateContractUnitTest::passthroughElevatedCallback);
        final MerchantApplication application = org.mockito.Mockito.mock(MerchantApplication.class);
        when(applicationRepository.getByID(APPLICATION_ID)).thenReturn(application);
        when(application.getAccountId()).thenReturn(ACCOUNT_ID);
        when(application.getShopName()).thenReturn("店铺甲");
        when(shopFactory.createShop("店铺甲", null, null))
                .thenReturn(new Shop(SHOP_ID, "店铺甲", null, null, ShopStatus.NORMAL));
        when(freightTemplateFactory.createDefaultFreightTemplate(SHOP_ID))
                .thenReturn(new FreightTemplate(90001L, SHOP_ID, "默认运费模板",
                        FreightRuleType.FREE, null, null, null,
                        FreightTemplateStatus.ENABLED, true));
    }

    /**
     * happy：审核通过（兼职开店）后创建并保存店铺默认运费模板——
     * 开店即有默认模板（FREE 初始规则由工厂契约承载，见工厂契约测试）。
     */
    @Test
    @DisplayName("审核通过即创建并保存店铺默认运费模板")
    void approve_createsAndSavesDefaultTemplate() {
        reviewUseCase.approve(APPLICATION_ID, REVIEWER_ID);

        verify(freightTemplateFactory).createDefaultFreightTemplate(SHOP_ID);
        verify(freightTemplateRepository).save(any(FreightTemplate.class));
    }

    /**
     * critical：默认模板创建顺序在店铺落库之后、账号-店铺关联绑定之前——
     * 编排顺序契约（店铺先存在、默认模板归属才有锚点）。
     */
    @Test
    @DisplayName("默认模板创建顺序：店铺落库之后")
    void approve_createsDefaultTemplateAfterShopSaved() {
        reviewUseCase.approve(APPLICATION_ID, REVIEWER_ID);

        final InOrder inOrder = inOrder(shopRepository, freightTemplateRepository);
        inOrder.verify(shopRepository).save(any(Shop.class));
        inOrder.verify(freightTemplateRepository).save(any(FreightTemplate.class));
    }

    /**
     * fail：默认模板创建失败 → 事务中止，账号-店铺关联绑定不发生
     * （任一工序失败整体回滚——不存在「审核通过但默认模板缺失」）。
     */
    @Test
    @DisplayName("默认模板创建失败中止后续关联绑定")
    void approve_defaultTemplateCreateFailure_abortsLaterSteps() {
        when(freightTemplateFactory.createDefaultFreightTemplate(anyLong()))
                .thenThrow(new BusinessException(
                        EcommerceBusinessCode.CATALOG_SHOP_REQUIRED.code(), "店铺不能为空", 400));

        assertThatThrownBy(() -> reviewUseCase.approve(APPLICATION_ID, REVIEWER_ID))
                .isInstanceOf(BusinessException.class);

        verify(accountShopRelRepository, never()).bind(anyLong(), anyLong());
    }
}