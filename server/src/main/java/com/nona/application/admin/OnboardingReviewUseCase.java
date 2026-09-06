package com.nona.application.admin;

import com.nona.api.admin.OnboardingAuditItem;
import com.nona.api.common.OnboardingStatus;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.factory.ShopFactory;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.domain.identity.repo.AccountShopRelRepository;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.events.Dispatcher;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.replica.LastWriteMarker;
import com.nona.inf.security.AuthUserCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 平台入驻审核用例：待审/全量列表、审核通过（同事务开店编排）、审核驳回（平台端，事务边界所在）。
 * <p>
 * 审核路径先取申请行写锁（{@link MerchantApplicationRepository#lockApplication}）
 * ——串行化同一申请的审核事务，并发审核只有一个状态迁移生效；随后加载聚合、
 * 状态机迁移（仅待审可审核，由聚合守卫）、落库。审核通过即在同一事务内完成
 * 跨域开店编排：创建店铺聚合（数据源自申请资料）+ 账号-店铺关联绑定，
 * 并同步发布 {@link ApplicationApprovedEvent}（通知旁路）——任一工序失败整体回滚，
 * 不存在「审核通过但店未开」的部分成功态；事务尾部主动失效商家用户上下文缓存
 * （关系变更即时生效）。审核人身份由认证上下文提供（web 层从跟踪上下文取当前
 * 账号 ID 传入）；申请表为平台全局数据（global），平台列表直接查询，无租户过滤语义。
 *
 * @author nona9961
 */
@Service
public class OnboardingReviewUseCase {

    /**
     * 入驻申请仓储
     */
    private final MerchantApplicationRepository applicationRepository;

    /**
     * 店铺工厂（开店创建入口，ID 生成 + 初始状态定型）
     */
    private final ShopFactory shopFactory;

    /**
     * 店铺仓储（开店落库）
     */
    private final ShopRepository shopRepository;

    /**
     * 账号-店铺关联仓储（审核通过时绑定）
     */
    private final AccountShopRelRepository accountShopRelRepository;

    /**
     * 用户上下文缓存（关系变更主动失效）
     */
    private final AuthUserCache authUserCache;

    /**
     * 事件分发器（审核通过事件发布）
     */
    private final Dispatcher dispatcher;

    /**
     * 写后自读窗口埋点（TD-08：审核通过 = 商家侧数据生效，标记商家账号
     * 使其 3s 内搜索立即可见——主体语义见用例契约）
     */
    private final LastWriteMarker lastWriteMarker;

    /**
     * 构造平台审核用例。
     *
     * @param applicationRepository 入驻申请仓储
     * @param shopFactory           店铺工厂
     * @param shopRepository        店铺仓储
     * @param accountShopRelRepository 账号-店铺关联仓储
     * @param authUserCache         用户上下文缓存
     * @param dispatcher            事件分发器
     * @param lastWriteMarker       写后窗口埋点（审核通过后标记商家）
     */
    public OnboardingReviewUseCase(MerchantApplicationRepository applicationRepository,
                                   ShopFactory shopFactory,
                                   ShopRepository shopRepository,
                                   AccountShopRelRepository accountShopRelRepository,
                                   AuthUserCache authUserCache,
                                   Dispatcher dispatcher,
                                   LastWriteMarker lastWriteMarker) {
        this.applicationRepository = applicationRepository;
        this.shopFactory = shopFactory;
        this.shopRepository = shopRepository;
        this.accountShopRelRepository = accountShopRelRepository;
        this.authUserCache = authUserCache;
        this.dispatcher = dispatcher;
        this.lastWriteMarker = lastWriteMarker;
    }

    /**
     * 申请列表（分页，提交时间正序——先提交的先审；按状态过滤可选，
     * null 表示全部状态）。global 表直读，无跨租户语义。
     *
     * @param status 申请状态过滤；null 表示全部
     * @param query  分页请求
     * @return 申请条目分页结果
     */
    public PageResult<OnboardingAuditItem> list(OnboardingStatus status, PageQuery query) {
        final int offset = Math.toIntExact(query.offset());
        final int limit = query.pageSize();
        final long total;
        final List<MerchantApplication> applications;
        if (status == null) {
            total = applicationRepository.countAll();
            applications = applicationRepository.listAll(offset, limit);
        } else {
            final ApplicationStatus domainStatus = toDomainStatus(status);
            total = applicationRepository.countByStatus(domainStatus);
            applications = applicationRepository.listByStatus(domainStatus, offset, limit);
        }
        return PageResult.of(applications.stream().map(OnboardingReviewUseCase::toItem).toList(),
                total, query);
    }

    /**
     * 审核通过（同事务开店编排）：申请行锁内加载聚合 → 状态机迁移（pending →
     * approved）→ 落库 → 创建店铺（名称取申请资料，logo/简介恒空）→ 店铺落库 →
     * 账号-店铺关联绑定（首绑；幂等已存在不视为失败）→ 同步发布审核通过领域
     * 事件（通知旁路，既有契约保留）→ 主动失效商家用户上下文缓存（末位；
     * Redis 不参与本地事务，提前失效无害——miss 回填重建）。任一工序失败 →
     * 本地事务整体回滚（申请迁移/店铺/关联全部撤销）。
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人账号 ID（认证上下文）
     * @return 新店铺 ID（审核通过即开店成功的结果契约）
     */
    @Transactional
    public Long approve(Long applicationId, Long reviewerId) {
        applicationRepository.lockApplication(applicationId);
        final MerchantApplication application = require(applicationId);
        application.approve(reviewerId);
        applicationRepository.save(application);
        final Shop shop = shopFactory.createShop(application.getShopName(), null, null);
        shopRepository.save(shop);
        accountShopRelRepository.bind(application.getAccountId(), shop.getId());
        dispatcher.dispatch(new ApplicationApprovedEvent(
                application.getId(), application.getAccountId(), application.getShopName()));
        authUserCache.delete(application.getAccountId());
        lastWriteMarker.markWrite(application.getAccountId());
        return shop.getId();
    }

    /**
     * 审核驳回：申请行锁内加载聚合 → 状态机迁移（pending → rejected，附原因）→ 落库。
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人账号 ID（认证上下文）
     * @param reason        驳回原因（必填，聚合守卫）
     */
    @Transactional
    public void reject(Long applicationId, Long reviewerId, String reason) {
        applicationRepository.lockApplication(applicationId);
        final MerchantApplication application = require(applicationId);
        application.reject(reviewerId, reason);
        applicationRepository.save(application);
    }

    /**
     * 申请加载守卫：目标申请不存在时按 404 拒绝。
     *
     * @param applicationId 申请 ID
     * @return 申请聚合
     */
    private MerchantApplication require(Long applicationId) {
        final MerchantApplication application = applicationRepository.getByID(applicationId);
        if (application == null) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_NOT_FOUND.code(),
                    "入驻申请不存在", 404);
        }
        return application;
    }

    /**
     * 契约状态 → 领域状态映射（值一一对应；映射点收敛在本用例）。
     *
     * @param status 契约申请状态
     * @return 领域申请状态
     */
    private static ApplicationStatus toDomainStatus(OnboardingStatus status) {
        return switch (status) {
            case PENDING -> ApplicationStatus.PENDING;
            case APPROVED -> ApplicationStatus.APPROVED;
            case REJECTED -> ApplicationStatus.REJECTED;
        };
    }

    /**
     * 领域申请 → API 审核条目（时间字段按 ISO 8601 字符串透出）。
     *
     * @param application 领域申请
     * @return API 审核条目
     */
    private static OnboardingAuditItem toItem(MerchantApplication application) {
        final String reviewTime = application.getReviewTime() == null
                ? null : application.getReviewTime().toString();
        final String createTime = application.getCreateTime() == null
                ? null : application.getCreateTime().toString();
        return new OnboardingAuditItem(application.getId(), application.getAccountId(),
                application.getShopName(), application.getContactName(), application.getContactPhone(),
                OnboardingStatus.valueOf(application.getStatus().name()),
                application.getRejectReason(), application.getReviewerId(),
                reviewTime, createTime);
    }
}