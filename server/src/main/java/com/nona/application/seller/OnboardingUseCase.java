package com.nona.application.seller;

import com.nona.api.seller.OnboardingApplicationRequest;
import com.nona.api.seller.OnboardingApplicationResponse;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.factory.MerchantApplicationFactory;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家入驻申请用例：提交 / 查看 / 编辑 / 重提编排（商家端，事务边界所在）。
 * <p>
 * 商家身份由认证上下文提供（controller 从跟踪上下文取当前账号 ID 传入），
 * 本用例不感知 token 机制。写入路径（提交/编辑/重提）统一先取账号写锁
 * （{@link MerchantApplicationRepository#lockAccount}）——串行化同一账号的
 * 申请写事务，one-pending 唯一性在并发窗口下也能收敛；随后加载/创建聚合、
 * 执行领域操作、落库，全在一个事务内完成。归属不变量：商家只能操作自己
 * 的申请，目标申请不属于当前账号时一律呈现为不存在（404，不泄露归属）。
 * 已通过（approved）为终态：编辑与重提由聚合守卫拒绝。
 *
 * @author nona9961
 */
@Service
public class OnboardingUseCase {

    /**
     * 入驻申请仓储
     */
    private final MerchantApplicationRepository applicationRepository;

    /**
     * 入驻申请工厂
     */
    private final MerchantApplicationFactory applicationFactory;

    /**
     * 构造入驻申请用例。
     *
     * @param applicationRepository 入驻申请仓储
     * @param applicationFactory    入驻申请工厂
     */
    public OnboardingUseCase(MerchantApplicationRepository applicationRepository,
                             MerchantApplicationFactory applicationFactory) {
        this.applicationRepository = applicationRepository;
        this.applicationFactory = applicationFactory;
    }

    /**
     * 提交入驻申请：账号锁内检查账号下无申请（含任一状态——单行模型每账号至多
     * 一个申请；待审中/已通过/驳回后未重提均拒绝直接新提交）→ 工厂创建 →
     * 落库（初始 pending，进入待审）。
     *
     * @param accountId 当前商家账号 ID（认证上下文）
     * @param request   申请资料
     * @return 新申请详情
     */
    @Transactional
    public OnboardingApplicationResponse submit(Long accountId, OnboardingApplicationRequest request) {
        applicationRepository.lockAccount(accountId);
        if (applicationRepository.findByAccountId(accountId).isPresent()) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_CONFLICT.code(),
                    "已存在入驻申请，请勿重复提交", 400);
        }
        final MerchantApplication application = applicationFactory.create(accountId,
                request.shopName(), request.contactName(), request.contactPhone());
        applicationRepository.save(application);
        return toResponse(application);
    }

    /**
     * 查看我的入驻申请（审核状态/驳回原因/审核记录）。
     *
     * @param accountId 当前商家账号 ID（认证上下文）
     * @return 申请详情；未提交返回 null（读路径不建根、不加锁）
     */
    public OnboardingApplicationResponse getMyApplication(Long accountId) {
        return applicationRepository.findByAccountId(accountId)
                .map(OnboardingUseCase::toResponse)
                .orElse(null);
    }

    /**
     * 编辑申请资料：账号锁内归属校验 → 聚合编辑（待审/驳回可编辑；
     * 已通过终态由聚合守卫拒绝）→ 落库。
     *
     * @param accountId     当前商家账号 ID（认证上下文）
     * @param applicationId 申请 ID（须属于当前账号）
     * @param request       新资料
     * @return 更新后的申请详情
     */
    @Transactional
    public OnboardingApplicationResponse update(Long accountId, Long applicationId,
                                               OnboardingApplicationRequest request) {
        applicationRepository.lockAccount(accountId);
        final MerchantApplication application = requireOwned(accountId, applicationId);
        application.editMaterials(request.shopName(), request.contactName(), request.contactPhone());
        applicationRepository.save(application);
        return toResponse(application);
    }

    /**
     * 驳回后重提：账号锁内归属校验 → 聚合重提（携带修订资料，迁移回待审
     * 并清空旧驳回结论）→ 落库。
     *
     * @param accountId     当前商家账号 ID（认证上下文）
     * @param applicationId 申请 ID（须属于当前账号）
     * @param request       修订后资料
     * @return 重提后的申请详情
     */
    @Transactional
    public OnboardingApplicationResponse resubmit(Long accountId, Long applicationId,
                                                  OnboardingApplicationRequest request) {
        applicationRepository.lockAccount(accountId);
        final MerchantApplication application = requireOwned(accountId, applicationId);
        application.resubmit(request.shopName(), request.contactName(), request.contactPhone());
        applicationRepository.save(application);
        return toResponse(application);
    }

    /**
     * 归属校验：按当前账号取出申请并校验 ID 匹配——账号下无申请或 ID 不属于
     * 当前账号一律呈现为不存在（不泄露归属）。
     *
     * @param accountId     当前商家账号 ID
     * @param applicationId 目标申请 ID
     * @return 归属校验通过的申请聚合
     */
    private MerchantApplication requireOwned(Long accountId, Long applicationId) {
        final MerchantApplication application = applicationRepository.findByAccountId(accountId)
                .orElse(null);
        if (application == null || !application.getId().equals(applicationId)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_NOT_FOUND.code(),
                    "入驻申请不存在", 404);
        }
        return application;
    }

    /**
     * 领域申请 → API 详情（时间字段按 ISO 8601 字符串透出）。
     *
     * @param application 领域申请
     * @return API 申请详情
     */
    private static OnboardingApplicationResponse toResponse(MerchantApplication application) {
        final String reviewTime = application.getReviewTime() == null
                ? null : application.getReviewTime().toString();
        final String createTime = application.getCreateTime() == null
                ? null : application.getCreateTime().toString();
        return new OnboardingApplicationResponse(application.getId(), application.getShopName(),
                application.getContactName(), application.getContactPhone(),
                com.nona.api.common.OnboardingStatus.valueOf(application.getStatus().name()),
                application.getRejectReason(), application.getReviewerId(),
                reviewTime, createTime);
    }
}