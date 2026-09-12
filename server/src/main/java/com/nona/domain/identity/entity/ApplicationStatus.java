package com.nona.domain.identity.entity;

/**
 * 入驻申请状态：入驻审核状态机的三态。
 * <p>
 * 迁移规则（收敛在 {@link MerchantApplication} 聚合方法内）：提交即
 * pending → 审核通过 approved（终态，不可再迁移） / 审核驳回 rejected
 * （可编辑，重提后回到 pending）。单行模型下任意时刻同一提交实体
 * 至多一个 pending 申请（account_id 唯一约束兜底）。
 *
 * @author nona9961
 */
public enum ApplicationStatus {

    /**
     * 待审核：提交后进入；审核动作（通过/驳回）的合法前置状态。
     */
    PENDING,

    /**
     * 已通过：终态；审核通过发布领域事件（供编排创建店铺），申请不可再修改。
     */
    APPROVED,

    /**
     * 已驳回：附驳回原因；可编辑资料并重提（迁移回 pending）。
     */
    REJECTED
}