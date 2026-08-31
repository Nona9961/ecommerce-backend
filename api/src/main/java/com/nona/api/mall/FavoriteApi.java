package com.nona.api.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 买家收藏契约（/mall/favorites，BUYER 角色）：商品 / 店铺收藏、取消收藏、收藏列表。
 * <p>
 * 三个操作均幂等：重复收藏不报错不重复生成（同一买家 + 同类型 + 同目标唯一）；
 * 取消不存在的收藏不报错。服务端实现位于 server 模块 web 层，
 * 当前买家身份从认证上下文（JWT uid）取，不来自请求体。
 *
 * @author nona9961
 */
public interface FavoriteApi {

    /**
     * 收藏（商品或店铺）：已收藏则幂等成功（不重复）。
     *
     * @param request 收藏请求（目标类型 + 目标 ID）
     * @return 成功响应
     */
    HttpResponse<Void> favorite(FavoriteRequest request);

    /**
     * 取消收藏（商品或店铺）：不存在该收藏则幂等成功（不报错）。
     *
     * @param request 收藏请求（目标类型 + 目标 ID）
     * @return 成功响应
     */
    HttpResponse<Void> unfavorite(FavoriteRequest request);

    /**
     * 收藏列表（分页，按收藏时间倒序）。
     *
     * @param targetType 收藏目标类型过滤；null 表示不过滤（全部类型）
     * @param pageNum    页码，从 1 开始（由实现侧按 {@link PageQuery} 归一化）
     * @param pageSize   每页条数（默认 10，上限 100；归一化同左）
     * @return 分页收藏条目
     */
    HttpResponse<PageResult<FavoriteItem>> list(String targetType, int pageNum, int pageSize);
}