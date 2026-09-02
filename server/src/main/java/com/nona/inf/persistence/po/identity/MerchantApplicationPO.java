package com.nona.inf.persistence.po.identity;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 入驻申请聚合根持久化对象（onboarding_application 表，global）：聚合根主表。
 * <p>
 * 主键 id = 申请独立主键（Snowflake）；account_id 为业务关联列（提交实体，
 * 唯一约束——每个提交实体至多一行申请，任意时刻至多一个 pending 由状态机
 * 迁移保证）；审核字段自带：状态 + 驳回原因 + 审核人 + 审核时间（无独立
 * 审核工单表）。申请发生在店铺创建之前，商家此刻尚无租户归属，
 * 属平台全局数据（BasePO），不随店铺租户隔离。
 *
 * @author nona9961
 */
@Entity
@Table(name = "onboarding_application", uniqueConstraints = {
        @UniqueConstraint(name = "uk_onboarding_account", columnNames = "account_id")
})
public class MerchantApplicationPO extends BasePO {

    /**
     * 提交实体（商家账号 ID，业务关联列）
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 店铺名（申请资料）
     */
    @Column(nullable = false, length = 128)
    private String shopName;

    /**
     * 联系人（申请资料）
     */
    @Column(nullable = false, length = 64, name = "contact_name")
    private String contactName;

    /**
     * 联系方式（申请资料）
     */
    @Column(nullable = false, length = 32, name = "contact_phone")
    private String contactPhone;

    /**
     * 审核状态（PENDING / APPROVED / REJECTED）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApplicationStatus status;

    /**
     * 驳回原因（审核字段自带；仅驳回时有值）
     */
    @Column(length = 512, name = "reject_reason")
    private String rejectReason;

    /**
     * 审核人（审核字段自带）
     */
    @Column(name = "reviewer_id")
    private Long reviewerId;

    /**
     * 审核时间（审核字段自带）
     */
    @Column(name = "review_time")
    private LocalDateTime reviewTime;

    /**
     * 提交实体。
     *
     * @return 商家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置提交实体。
     *
     * @param accountId 商家账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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
     * 设置店铺名。
     *
     * @param shopName 店铺名
     */
    public void setShopName(String shopName) {
        this.shopName = shopName;
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
     * 设置联系人。
     *
     * @param contactName 联系人
     */
    public void setContactName(String contactName) {
        this.contactName = contactName;
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
     * 设置联系方式。
     *
     * @param contactPhone 联系方式
     */
    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    /**
     * 审核状态。
     *
     * @return 状态
     */
    public ApplicationStatus getStatus() {
        return status;
    }

    /**
     * 设置审核状态。
     *
     * @param status 状态
     */
    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    /**
     * 驳回原因。
     *
     * @return 驳回原因；未驳回为 null
     */
    public String getRejectReason() {
        return rejectReason;
    }

    /**
     * 设置驳回原因。
     *
     * @param rejectReason 驳回原因
     */
    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
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
     * 设置审核人。
     *
     * @param reviewerId 审核人账号 ID
     */
    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
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
     * 设置审核时间。
     *
     * @param reviewTime 审核时间
     */
    public void setReviewTime(LocalDateTime reviewTime) {
        this.reviewTime = reviewTime;
    }
}