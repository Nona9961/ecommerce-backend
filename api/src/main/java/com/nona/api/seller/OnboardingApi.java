package com.nona.api.seller;

import com.nona.api.HttpResponse;

/**
 * 商家入驻申请契约（/seller/onboarding，SELLER 角色）：提交申请、查看审核状态、
 * 编辑资料、驳回后重提。
 * <p>
 * 提交：首次创建申请（进入待审）；已存在申请（含待审中）拒绝——每个提交实体
 * 至多一个申请。查看：返回当前申请详情，未提交返回 data=null（200，前端展示
 * 「未提交」）。编辑：待审/驳回状态可改资料（已通过拒绝）。重提：仅驳回状态
 * 可发起（携带修订资料，迁移回待审并清空旧驳回结论）。
 * 服务端实现位于 server 模块 web 层，当前商家身份从认证上下文（JWT uid）取。
 *
 * @author nona9961
 */
public interface OnboardingApi {

    /**
     * 提交入驻申请（创建，状态进入待审）。
     *
     * @param request 申请资料
     * @return 申请详情（含分配的 ID）
     */
    HttpResponse<OnboardingApplicationResponse> submit(OnboardingApplicationRequest request);

    /**
     * 查看我的入驻申请（审核状态/驳回原因/审核记录）。
     *
     * @return 申请详情；未提交返回 data=null
     */
    HttpResponse<OnboardingApplicationResponse> getMyApplication();

    /**
     * 编辑申请资料（待审/驳回状态；已通过拒绝）。
     *
     * @param applicationId 申请 ID
     * @param request       新资料
     * @return 更新后的申请详情
     */
    HttpResponse<OnboardingApplicationResponse> update(Long applicationId, OnboardingApplicationRequest request);

    /**
     * 驳回后重提（携带修订资料，迁移回待审）。
     *
     * @param applicationId 申请 ID
     * @param request       修订后资料
     * @return 重提后的申请详情
     */
    HttpResponse<OnboardingApplicationResponse> resubmit(Long applicationId, OnboardingApplicationRequest request);
}