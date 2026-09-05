package com.nona.domain.search.ports;

/**
 * 搜索检索条件（search 域查询入参，纯语义载体）。
 * <p>
 * 字段语义：
 * <ul>
 *     <li>keyword：关键词，匹配商品标题/描述（ILIKE，大小写不敏感）；
 *         空白/未传视为不限定；</li>
 *     <li>categoryId / brandId / shopId：平台一级类目/品牌/店铺过滤
 *         （与业务模型分类/品牌/店铺三过滤器一致，D7-srch1）；</li>
 *     <li>minPrice / maxPrice：价格闭区间（分，单位与商品 SKU 价一致；
 *         两端含端点；null=开区间）；</li>
 *     <li>sort：排序（null=默认 TIME_DESC）。</li>
 * </ul>
 * 参数合法性校验（价格区间非负且不倒挂等）收敛在服务实现层校验位
 * （与 PageQuery 在 api 层归一化同构：入参把关在调用链入口），本记录
 * 保持纯数据形态。
 *
 * @param keyword   关键词（可空；空白等价 null）
 * @param categoryId 平台一级类目 ID（可空）
 * @param brandId   品牌 ID（可空）
 * @param shopId    店铺 ID（可空）
 * @param minPrice  价格下界（分，含端点；null=不限）
 * @param maxPrice  价格上界（分，含端点；null=不限）
 * @param sort      排序（null=默认 TIME_DESC）
 * @author nona9961
 */
public record SearchCriteria(
        String keyword,
        Long categoryId,
        Long brandId,
        Long shopId,
        Long minPrice,
        Long maxPrice,
        SearchSort sort
) {
}