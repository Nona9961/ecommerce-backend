package com.nona.api.admin;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 平台商品审核契约（/admin/products…，ADMIN 角色）：待审/全量商品列表、
 * 审核通过、审核驳回。
 * <p>
 * 审核是商品聚合自身状态机迁移（审核 = 状态转换，无独立审核单）；平台
 * 待审列表 = 按商品状态筛选分页（跨店铺全集，管理员视角可审核任意店铺
 * 商品）。列表按状态过滤（缺省全部）分页，资料概要可见（图片/SKU 计数
 * 供审核人判断完整度）；审核动作仅对待审核商品合法（状态机守卫，重复
 * 审核拒绝）；驳回必须附原因（商家据此修改重提）；审核结论（审核人/
 * 时间/原因）随商品版本链落位（通过/驳回各插一行审核结论版本）。
 * 服务端实现位于 server 模块 web 层，审核人身份从认证上下文（JWT uid）取。
 *
 * @author nona9961
 */
public interface ProductReviewApi {

    /**
     * 商品审核列表（分页；按商品状态过滤可选，管理员视角跨店铺全集）。
     *
     * @param status   商品状态过滤；null 表示不过滤（全部）
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数（默认 10，上限 100）
     * @return 商品条目分页结果（按创建序，先创建的先审）
     */
    HttpResponse<PageResult<ProductReviewItem>> list(String status, int pageNum, int pageSize);

    /**
     * 审核通过：待审核 → 在售；存在待审草稿（敏感字段编辑分流）时待审
     * 内容覆盖正式内容（买家开始可见新内容），并插一行审核通过结论版本。
     *
     * @param productId 商品 ID（管理员视角任意店铺）
     * @return 成功响应
     */
    HttpResponse<Void> approve(Long productId);

    /**
     * 审核驳回：待审核回草稿（附原因，商家可修改后重提；待审草稿作废），
     * 并插一行审核驳回结论版本（承载驳回原因）。
     *
     * @param productId 商品 ID（管理员视角任意店铺）
     * @param request   驳回原因（必填）
     * @return 成功响应
     */
    HttpResponse<Void> reject(Long productId, ProductRejectRequest request);
}