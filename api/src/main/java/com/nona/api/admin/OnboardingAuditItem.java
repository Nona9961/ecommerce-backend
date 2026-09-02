package com.nona.api.admin;

import com.nona.api.common.OnboardingStatus;

/**
 * 入驻申请审核条目（平台待审列表/全量列表项）：申请资料完整可见。
 * <p>
 * 审核记录（审核人/时间）与驳回原因随申请自带；状态过滤与分页在查询侧完成。
 * 时间字段为 ISO 8601 字符串（与审计字段一致）。
 *
 * @param id           申请 ID
 * @param accountId    提交实体（商家账号 ID）
 * @param shopName     店铺名
 * @param contactName  联系人
 * @param contactPhone 联系方式
 * @param status       审核状态
 * @param rejectReason 驳回原因；未驳回为 null
 * @param reviewerId   审核人；未审核为 null
 * @param reviewTime   审核时间（ISO 8601）；未审核为 null
 * @param createTime   提交时间（ISO 8601）
 */
public record OnboardingAuditItem(
        Long id,
        Long accountId,
        String shopName,
        String contactName,
        String contactPhone,
        OnboardingStatus status,
        String rejectReason,
        Long reviewerId,
        String reviewTime,
        String createTime
) {
}