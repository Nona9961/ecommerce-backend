package com.nona.api.admin;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 平台端用户管理契约：用户列表查询（分页示范接口）。
 * <p>
 * 分页入参统一使用 {@link com.nona.api.common.PageQuery}（pageNum/pageSize），
 * 返回统一使用 {@link com.nona.api.common.PageResult}；后续各端列表接口均按此形态扩展。
 *
 * @author nona9961
 */
public interface AdminUserApi {

    /**
     * 分页查询平台用户（买家/商家账号）。
     *
     * @param query 分页参数（页码/每页条数，构造时自动归一化）
     * @return 用户摘要分页结果
     */
    HttpResponse<PageResult<UserSummary>> listUsers(PageQuery query);
}
