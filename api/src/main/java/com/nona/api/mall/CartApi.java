package com.nona.api.mall;

import com.nona.api.HttpResponse;

import java.util.List;

/**
 * 买家购物车契约（/mall/cart，BUYER 角色）：加购 / 改量 / 移除 / 勾选 /
 * 全选 / 分组列表。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>同 SKU 单条目</b>：同一买家同一 SKU 在购物车至多一条，重复
 *         加购数量累加；</li>
 *     <li><b>数量校验</b>：数量 > 0 且 ≤ 该 SKU 买家可见可售上限（超上限
 *         拒绝写入，冲突语义 409）；</li>
 *     <li><b>勾选持久化</b>：勾选标记落库（跨请求保持），结算只取勾选项；
 *         全选 = 全部条目置位（空车幂等）；</li>
 *     <li><b>移除幂等</b>：删除不存在的条目幂等成功（重复点击/列表过期
 *         不报错）；改量与勾选目标不存在按不存在提示（404）；</li>
 *     <li><b>买家维度</b>：购物车数据买家专属（跨买家互不可见），当前
 *         买家身份从认证上下文取，不来自请求体。</li>
 * </ul>
 * 服务端实现位于 server 模块 web 层。
 *
 * @author nona9961
 */
public interface CartApi {

    /**
     * 加购（选 SKU + 数量）：商品在售校验 + SKU 归属校验 + 数量 ≤ 可售
     * 上限；同 SKU 已存在则数量累加。
     *
     * @param request 加购请求（商品 / SKU / 数量）
     * @return 成功响应
     */
    HttpResponse<Void> add(AddCartRequest request);

    /**
     * 修改条目数量（按 SKU 定位，绝对量替换）：数量 > 0 且 ≤ 可售上限。
     *
     * @param skuId   条目 SKU ID
     * @param request 新数量
     * @return 成功响应
     */
    HttpResponse<Void> changeQuantity(Long skuId, UpdateQuantityRequest request);

    /**
     * 移除条目（按 SKU 定位）：不存在幂等成功。
     *
     * @param skuId 条目 SKU ID
     * @return 成功响应
     */
    HttpResponse<Void> remove(Long skuId);

    /**
     * 批量勾选/取消勾选条目（按 SKU 列表定位）。
     *
     * @param request 目标 SKU 列表 + 勾选状态
     * @return 成功响应
     */
    HttpResponse<Void> check(CheckedBatchRequest request);

    /**
     * 全选/全不选（全部条目置位）。
     *
     * @param request 勾选状态
     * @return 成功响应
     */
    HttpResponse<Void> checkAll(CheckAllRequest request);

    /**
     * 购物车列表（按店铺分组：店铺名 + 数量合计 + 条目，含勾选标记）。
     *
     * @return 分组列表；空购物车返回空列表
     */
    HttpResponse<List<CartGroupView>> list();
}