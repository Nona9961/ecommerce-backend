package com.nona.api.admin;

import com.nona.api.HttpResponse;

/**
 * 品牌库契约（/admin/brands，ADMIN 角色）：品牌库维护。
 * <p>
 * 品牌为平台全局货架基础数据，商家创建商品时可选挂品牌（挂载联动属
 * 商品域后续工作）。「删除」= 禁用（disable-not-delete 软删语义）：禁用后
 * 既有商品保持可见，新商品不可挂载（商品侧挂载守卫属商品域后续工作）；
 * DELETE 端点与 disable 端点语义一致，均保留行（名称全局唯一，
 * 禁用态名称亦不可复用）。服务端实现位于 server 模块 web 层。
 *
 * @author nona9961
 */
public interface BrandApi {

    /**
     * 新增品牌：名称必填非空且全局唯一；logo 可空；初始状态 ENABLED。
     *
     * @param request 品牌名称与 logo
     * @return 新建品牌
     */
    HttpResponse<BrandItem> create(BrandRequest request);

    /**
     * 品牌列表（按创建序，ID 升序；状态过滤可选）。
     *
     * @param status 状态过滤（ENABLED / DISABLED）；null 表示不过滤（全部）
     * @return 品牌列表；无数据为空列表
     */
    HttpResponse<java.util.List<BrandItem>> list(String status);

    /**
     * 更新品牌：名称必填非空且全局唯一（改回自身原名合法）；logo 可空
     * （传 null 清除）。
     *
     * @param brandId 品牌 ID（不存在则 404）
     * @param request 新名称与新 logo
     * @return 更新后的品牌
     */
    HttpResponse<BrandItem> update(Long brandId, BrandRequest request);

    /**
     * 删除品牌（「删除」= 禁用软删）：状态迁移 DISABLED，行保留。
     *
     * @param brandId 品牌 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> delete(Long brandId);

    /**
     * 禁用品牌（与 {@link #delete} 语义一致，显式状态动作）。
     *
     * @param brandId 品牌 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> disable(Long brandId);

    /**
     * 启用品牌（禁用态恢复）。
     *
     * @param brandId 品牌 ID（不存在则 404）
     * @return 成功响应
     */
    HttpResponse<Void> enable(Long brandId);
}