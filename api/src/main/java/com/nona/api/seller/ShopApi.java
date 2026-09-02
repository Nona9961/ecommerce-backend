package com.nona.api.seller;

import com.nona.api.HttpResponse;

/**
 * 商家端店铺契约（/seller/shop…，SELLER 角色）：店铺信息查询/编辑与店铺分类 CRUD。
 * <p>
 * 当前店铺由认证上下文定位（登录时账号-店铺关联写入用户上下文，过滤器填充
 * 请求租户=当前店铺 ID），本契约不接收店铺 ID 参数；店铺分类归属于当前店铺，
 * 跨店铺分类访问按不存在呈现（404，不泄露归属）。分类删除为物理删除
 * （商品引用守卫属商品域后续工作）。
 *
 * @author nona9961
 */
public interface ShopApi {

    /**
     * 当前店铺详情（含店铺分类列表，按展示排序升序）。
     *
     * @return 店铺详情
     */
    HttpResponse<ShopDetail> getShop();

    /**
     * 编辑当前店铺信息（名称必填，logo/简介可空；店铺状态不接受商家编辑）。
     *
     * @param request 店铺信息
     * @return 更新后的店铺详情
     */
    HttpResponse<ShopDetail> updateShop(ShopInfoRequest request);

    /**
     * 新增店铺分类：排序自动分配（当前最大 +1，从 1 起）。
     *
     * @param request 分类名称
     * @return 新建分类（含分配的排序）
     */
    HttpResponse<ShopCategoryItem> addCategory(ShopCategoryRequest request);

    /**
     * 店铺分类改名（排序保持不变）。
     *
     * @param categoryId 分类 ID（必须属于当前店铺，否则 404）
     * @param request    新名称
     * @return 更新后的分类
     */
    HttpResponse<ShopCategoryItem> renameCategory(Long categoryId, ShopCategoryRequest request);

    /**
     * 删除店铺分类（物理删除；其余分类排序不重排）。
     *
     * @param categoryId 分类 ID（必须属于当前店铺，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> removeCategory(Long categoryId);
}