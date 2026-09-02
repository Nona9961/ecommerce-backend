package com.nona.api.seller;

import com.nona.api.common.OnboardingStatus;

/**
 * 入驻申请详情（商家端查看入驻审核状态）。
 * <p>
 * 状态三态（PENDING/APPROVED/REJECTED）可见；驳回原因仅在驳回时有值；
 * 审核人/审核时间透出（审核记录随申请自带）；时间字段为 ISO 8601 字符串
 * （本地时间存储格式，与审计字段一致）。
 *
 * @param id           申请 ID
 * @param shopName     店铺名
 * @param contactName  联系人
 * @param contactPhone 联系方式
 * @param status       审核状态
 * @param rejectReason 驳回原因；未驳回为 null
 * @param reviewerId   审核人；未审核为 null
 * @param reviewTime   审核时间（ISO 8601）；未审核为 null
 * @param createTime   提交时间（ISO 8601）
 */
public record OnboardingApplicationResponse(
        Long id,
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