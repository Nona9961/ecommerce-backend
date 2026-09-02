package com.nona.domain.identity.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;

/**
 * 入驻申请聚合根（onboarding_application 主表，global）：
 * 商家提交的入驻申请资料 + 审核状态机 + 驳回原因 + 审核记录（审核人/时间）。
 * <p>
 * 聚合内容：申请资料（店铺名/联系方式等）+ 状态机
 * （pending → approved / rejected，rejected 可编辑重提）+ 驳回原因 +
 * 审核记录（审核人/时间），审核字段自带（无独立审核工单表）。
 * 关键不变量：每个提交实体至多一个 pending 申请（重提必须基于被驳回版本，
 * 即同一申请行经 rejected → pending 迁移；account_id 唯一约束兜底并发）；
 * approved 后不可变（后续变更走店铺聚合）。
 * 不变量收敛在本聚合：所有状态迁移与资料修改都经守卫方法，包外无直接
 * 字段变更路径。创建必须经由 {@link com.nona.domain.identity.factory.MerchantApplicationFactory}
 * + {@link com.nona.util.IDUtils#generateID()}；提交时间由持久化层审计
 * 字段回填（新建时为 null）。
 *
 * @author nona9961
 */
public class MerchantApplication {

    /**
     * 申请主键（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 提交实体（商家账号 ID，业务关联列；account_id 唯一）
     */
    private final Long accountId;

    /**
     * 店铺名（申请资料）
     */
    private String shopName;

    /**
     * 联系人（申请资料）
     */
    private String contactName;

    /**
     * 联系方式（申请资料）
     */
    private String contactPhone;

    /**
     * 审核状态（状态机三态）
     */
    private ApplicationStatus status;

    /**
     * 驳回原因（审核字段自带；仅 rejected 时有值）
     */
    private String rejectReason;

    /**
     * 审核人（审核字段自带；未审核为 null）
     */
    private Long reviewerId;

    /**
     * 审核时间（审核字段自带；未审核为 null）
     */
    private LocalDateTime reviewTime;

    /**
     * 提交时间（持久化层审计回填；新建为 null）
     */
    private final LocalDateTime createTime;

    /**
     * 构造申请（仅工厂创建与仓储加载重建调用）。
     *
     * @param id            申请主键
     * @param accountId     提交实体（商家账号 ID）
     * @param shopName      店铺名
     * @param contactName   联系人
     * @param contactPhone  联系方式
     * @param status        审核状态
     * @param rejectReason  驳回原因（可为 null）
     * @param reviewerId    审核人（可为 null）
     * @param reviewTime    审核时间（可为 null）
     * @param createTime    提交时间（新建为 null，持久化层回填）
     */
    public MerchantApplication(Long id, Long accountId, String shopName, String contactName,
                               String contactPhone, ApplicationStatus status, String rejectReason,
                               Long reviewerId, LocalDateTime reviewTime, LocalDateTime createTime) {
        this.id = id;
        this.accountId = accountId;
        this.shopName = shopName;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.status = status;
        this.rejectReason = rejectReason;
        this.reviewerId = reviewerId;
        this.reviewTime = reviewTime;
        this.createTime = createTime;
    }

    /**
     * 申请主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 提交实体（商家账号 ID）。
     *
     * @return 账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 店铺名。
     *
     * @return 店铺名
     */
    public String getShopName() {
        return shopName;
    }

    /**
     * 联系人。
     *
     * @return 联系人
     */
    public String getContactName() {
        return contactName;
    }

    /**
     * 联系方式。
     *
     * @return 联系方式
     */
    public String getContactPhone() {
        return contactPhone;
    }

    /**
     * 审核状态（状态机三态）。
     *
     * @return 状态
     */
    public ApplicationStatus getStatus() {
        return status;
    }

    /**
     * 驳回原因（审核字段自带）。
     *
     * @return 驳回原因；未驳回为 null
     */
    public String getRejectReason() {
        return rejectReason;
    }

    /**
     * 审核人。
     *
     * @return 审核人账号 ID；未审核为 null
     */
    public Long getReviewerId() {
        return reviewerId;
    }

    /**
     * 审核时间。
     *
     * @return 审核时间；未审核为 null
     */
    public LocalDateTime getReviewTime() {
        return reviewTime;
    }

    /**
     * 提交时间。
     *
     * @return 提交时间；新建为 null（持久化层回填）
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 编辑申请资料：pending 与 rejected 状态可编辑（rejected 下编辑保存草稿待重提）；
     * approved 为终态拒绝（不可变，后续变更走店铺聚合）。
     *
     * @param shopName     新店铺名
     * @param contactName  新联系人
     * @param contactPhone 新联系方式
     */
    public void editMaterials(String shopName, String contactName, String contactPhone) {
        assertMaterials(shopName, contactName, contactPhone);
        if (status == ApplicationStatus.APPROVED) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(),
                    "申请已通过，资料不可修改", 400);
        }
        this.shopName = shopName;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
    }

    /**
     * 重提（提交修订后的资料）：仅 rejected 可发起——重提必须基于被驳回版本；
     * 迁移回 pending 并清空旧审核结论（驳回原因/审核人/审核时间），等待重新审核。
     *
     * @param shopName     新店铺名
     * @param contactName  新联系人
     * @param contactPhone 新联系方式
     */
    public void resubmit(String shopName, String contactName, String contactPhone) {
        assertMaterials(shopName, contactName, contactPhone);
        if (status != ApplicationStatus.REJECTED) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(),
                    "仅被驳回的申请可以重提", 400);
        }
        this.shopName = shopName;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.status = ApplicationStatus.PENDING;
        this.rejectReason = null;
        this.reviewerId = null;
        this.reviewTime = null;
    }

    /**
     * 审核通过：仅 pending 可迁移；approved 为终态不可再迁移。
     *
     * @param reviewerId 审核人账号 ID
     */
    public void approve(Long reviewerId) {
        assertPendingReview(reviewerId);
        this.status = ApplicationStatus.APPROVED;
        this.reviewerId = reviewerId;
        this.reviewTime = LocalDateTime.now();
        this.rejectReason = null;
    }

    /**
     * 审核驳回：仅 pending 可迁移；驳回必须附原因。
     *
     * @param reviewerId 审核人账号 ID
     * @param reason     驳回原因（非空）
     */
    public void reject(Long reviewerId, String reason) {
        assertPendingReview(reviewerId);
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(),
                    "驳回必须附原因", 400);
        }
        this.status = ApplicationStatus.REJECTED;
        this.reviewerId = reviewerId;
        this.reviewTime = LocalDateTime.now();
        this.rejectReason = reason.trim();
    }

    /**
     * 审核前置守卫：状态必须为 pending 且审核人非空（迁移合法性统一入口）。
     *
     * @param reviewerId 审核人账号 ID
     */
    private void assertPendingReview(Long reviewerId) {
        if (status != ApplicationStatus.PENDING) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(),
                    "仅待审核的申请可以审核，当前状态：" + status, 400);
        }
        if (reviewerId == null) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(),
                    "审核人不能为空", 400);
        }
    }

    /**
     * 资料形态守卫（编辑与重提共用；API 层已由 JSR-380 兜底，领域层再断言
     * 一次防止绕过 API 的直接调用路径）。
     *
     * @param shopName     店铺名
     * @param contactName  联系人
     * @param contactPhone 联系方式
     */
    private static void assertMaterials(String shopName, String contactName, String contactPhone) {
        if (StringUtils.isBlank(shopName)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "店铺名不能为空", 400);
        }
        if (StringUtils.isBlank(contactName)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "联系人不能为空", 400);
        }
        if (StringUtils.isBlank(contactPhone)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "联系方式不能为空", 400);
        }
    }
}