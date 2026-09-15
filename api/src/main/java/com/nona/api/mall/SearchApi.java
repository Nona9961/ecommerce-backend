package com.nona.api.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageResult;

/**
 * 商品搜索契约（GET /mall/search，BUYER 角色）——冻结：
 * 端点形态由前端约定钉死（searchApi.ts 逐参数对应），
 * 复用后端 search 域 ProductSearchService（PG 镜像读），
 * 后端按同形状接入，字段不再演进。
 * <p>
 * 参数语义：
 * <ul>
 *     <li><b>金额纪律</b>：minPrice/maxPrice 为分（前端 api-client 层
 *         上行 ×100）；非法价格区间（负数/倒挂）由服务实现校验位拒绝
 *         （search.invalid_price_range 400）；</li>
 *     <li><b>排序</b>：sort 为 SearchSort 枚举名（SALES_DESC /
 *         PRICE_ASC / TIME_DESC；null = 默认 TIME_DESC），非法值 400
 *         fail-closed；</li>
 *     <li><b>分页</b>：pageNum/pageSize 经 PageQuery 归一化（第 1 页起、
 *         默认 10、上限 100）；</li>
 *     <li><b>结果范围</b>：视图侧已过滤在售 + 有货商品（空搜索返回
 *         空列表 + total 0，非 null）；</li>
 *     <li><b>写后自读</b>：当前登录买家账号 ID 由 web 层从认证上下文
 *         取（写者本人视角 3s 窗口主库读，匿名无此面——/mall/** 必
 *         有身份）。</li>
 * </ul>
 *
 * @author nona9961
 */
public interface SearchApi {

    /**
     * 商品搜索（分页）。
     *
     * @param keyword    关键词（匹配标题/描述，大小写不敏感；可空 =
     *                   不限定）
     * @param categoryId 平台一级类目 ID（可空）
     * @param brandId    品牌 ID（可空）
     * @param shopId     店铺 ID（可空）
     * @param minPrice   价格下界（分，含端点；可空 = 不限）
     * @param maxPrice   价格上界（分，含端点；可空 = 不限）
     * @param sort       排序枚举名（可空 = 默认 TIME_DESC；非法值 400）
     * @param pageNum    页码，从 1 开始（PageQuery 归一化）
     * @param pageSize   每页条数（默认 10，上限 100；归一化同左）
     * @return 分页卡片结果（minPrice/salesTotal 为分）
     */
    HttpResponse<PageResult<SearchCard>> search(String keyword, Long categoryId, Long brandId,
                                                Long shopId, Long minPrice, Long maxPrice,
                                                String sort, int pageNum, int pageSize);
}