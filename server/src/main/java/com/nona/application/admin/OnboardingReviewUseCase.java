package com.nona.application.admin;

import com.nona.api.admin.OnboardingAuditItem;
import com.nona.api.common.OnboardingStatus;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.events.Dispatcher;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 平台入驻审核用例：待审/全量列表、审核通过、审核驳回（平台端，事务边界所在）。
 * <p>
 * 审核路径先取申请行写锁（{@link MerchantApplicationRepository#lockApplication}）
 * ——串行化同一申请的审核事务，并发审核只有一个状态迁移生效；随后加载聚合、
 * 状态机迁移（仅待审可审核，由聚合守卫）、落库。审核通过后在同一事务内
 * 同步发布 {@link ApplicationApprovedEvent}（进程内总线；消费方编排创建店铺
 * 聚合 + 账号-店铺关联——落点在后续版本接入的编排，本用例不含开店）。
 * 审核人身份由认证上下文提供（web 层从跟踪上下文取当前账号 ID 传入）；
 * 申请表为平台全局数据（global），平台列表直接查询，无租户过滤语义。
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
     * 事件分发器（审核通过事件发布）
     */
    private final Dispatcher dispatcher;

    /**
     * 构造平台审核用例。
     *
     * @param applicationRepository 入驻申请仓储
     * @param dispatcher            事件分发器
     */
    public OnboardingReviewUseCase(MerchantApplicationRepository applicationRepository,
                                   Dispatcher dispatcher) {
        this.applicationRepository = applicationRepository;
        this.dispatcher = dispatcher;
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
     * 审核通过：申请行锁内加载聚合 → 状态机迁移（pending → approved）→ 落库 →
     * 同步发布审核通过领域事件（供开店编排消费）。
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人账号 ID（认证上下文）
     */
    @Transactional
    public void approve(Long applicationId, Long reviewerId) {
        applicationRepository.lockApplication(applicationId);
        final MerchantApplication application = require(applicationId);
        application.approve(reviewerId);
        applicationRepository.save(application);
        dispatcher.dispatch(new ApplicationApprovedEvent(
                application.getId(), application.getAccountId(), application.getShopName()));
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