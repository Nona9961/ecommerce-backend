package com.nona.api.admin;

import com.nona.api.HttpResponse;

/**
 * 平台分类契约（/admin/categories，ADMIN 角色）：平台一级分类维护。
 * <p>
 * 平台分类为全局货架组织单位，与商家店铺分类（商家端 /seller/shop/categories）
 * 相互独立（两套体系无关联逻辑）。「删除」= 禁用（disable-not-delete 软删语义）：
 * 禁用后既有商品保持历史挂载，新商品不可挂载（商品侧挂载守卫属商品域
 * 后续工作）；DELETE 端点与 disable 端点语义一致，均保留行
 * （名称全局唯一，禁用态名称亦不可复用）。服务端实现位于 server 模块 web 层。
 *
 * @author nona9961
 */
public interface PlatformCategoryApi {

    /**
     * 新增平台分类：名称必填非空且全局唯一；排序可空（自动取当前最大 + 1）
     * 或显式正数；初始状态 ENABLED。
     *
     * @param request 分类名称与排序
     * @return 新建分类
     */
    HttpResponse<PlatformCategoryItem> create(PlatformCategoryRequest request);

    /**
     * 平台分类列表（按展示排序升序；状态过滤可选）。
     *
     * @param status 状态过滤（ENABLED / DISABLED）；null 表示不过滤（全部）
     * @return 分类列表；无数据为空列表
     */
    HttpResponse<java.util.List<PlatformCategoryItem>> list(String status);

    /**
     * 更新平台分类：名称必填非空且全局唯一（改回自身原名合法）；
     * 排序显式正数时更新，空缺保持。
     *
     * @param categoryId 分类 ID（不存在则 404）
     * @param request    新名称与新排序
     * @return 更新后的分类
     */
    HttpResponse<PlatformCategoryItem> update(Long categoryId, PlatformCategoryRequest request);

    /**
     * 删除平台分类（「删除」= 禁用软删）：状态迁移 DISABLED，行保留。
     *
     * @param categoryId 分类 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> delete(Long categoryId);

    /**
     * 禁用平台分类（与 {@link #delete} 语义一致，显式状态动作）。
     *
     * @param categoryId 分类 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> disable(Long categoryId);

    /**
     * 启用平台分类（禁用态恢复）。
     *
     * @param categoryId 分类 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> enable(Long categoryId);
}