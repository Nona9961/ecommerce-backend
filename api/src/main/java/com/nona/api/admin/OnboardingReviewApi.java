package com.nona.api.admin;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 平台入驻审核契约（/admin/onboarding，ADMIN 角色）：待审/全量申请列表、
 * 审核通过、审核驳回。
 * <p>
 * 列表按状态过滤（缺省全部）分页，资料完整可见；审核动作仅对待审申请合法
 * （状态机守卫，重复审核拒绝）；驳回必须附原因（商家据此修改重提）。
 * 审核通过发布领域事件（{@code ApplicationApproved}），店铺创建由后续
 * 版本接入的编排消费（本契约不含开店）。
 * 服务端实现位于 server 模块 web 层，审核人身份从认证上下文（JWT uid）取。
 *
 * @author nona9961
 */
public interface OnboardingReviewApi {

    /**
     * 入驻申请列表（分页；按状态过滤可选）。
     *
     * @param status   申请状态过滤；null 表示不过滤（全部）
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数（默认 10，上限 100）
     * @return 申请条目分页结果（提交时间正序，先提交的先审）
     */
    HttpResponse<PageResult<OnboardingAuditItem>> list(String status, int pageNum, int pageSize);

    /**
     * 审核通过：申请迁移至 approved 终态，并发布 ApplicationApproved 领域事件。
     *
     * @param applicationId 申请 ID
     * @return 成功响应
     */
    HttpResponse<Void> approve(Long applicationId);

    /**
     * 审核驳回：申请迁移至 rejected（附原因，可修改重提）。
     *
     * @param applicationId 申请 ID
     * @param request       驳回原因（必填）
     * @return 成功响应
     */
    HttpResponse<Void> reject(Long applicationId, ApplicationRejectRequest request);
}