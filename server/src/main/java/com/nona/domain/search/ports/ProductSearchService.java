package com.nona.domain.search.ports;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 商品搜索服务（search 域查询契约，纯读意图，引擎无关）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>数据源</b>：读 PG 镜像视图 product_search_view（CDC 最终
 *         一致，秒级延迟可接受）；本服务不触达主库，无写路径；</li>
 *     <li><b>结果范围</b>：视图侧已过滤在售（ON_SALE）+ 有货（可售汇总
 *         &gt; 0）商品，调用方无需重复判活（下架/审核中/无货不出现在
 *         搜索结果，B5 语义）；</li>
 *     <li><b>匹配形态</b>：标题/描述 ILIKE 关键词（大小写不敏感）+
 *         分类/价格区间/品牌/店铺过滤 + 销量/价格/时间三轴排序（后续
 *         引擎演进换实现不换签名）；</li>
 *     <li><b>租户语义</b>：平台级全局命中（买家视角，无店铺归属过滤），
 *         PG 侧无 Hibernate 租户过滤（放行形态）；</li>
 *     <li><b>事务</b>：读路径不开写事务。</li>
 * </ul>
 * 接口签名契约冻结：后续更换引擎实现（tsvector/外部引擎）不影响调用方。
 *
 * @author nona9961
 */
public interface ProductSearchService {

    /**
     * 商品搜索（分页，无账号形态——既有契约保留）。
     * <p>
     * 账号缺省 = 无写后窗口判定：本次查询按静态装配走 PG 读库
     * （replica 通道）。行为与既有 2 参入口完全一致（匿名/未登录调用与
     * 非写者账号均可走本重载）。
     *
     * @param criteria 检索条件（关键词/类目/品牌/价格区间/店铺/排序；
     *                 非法价格区间按 {@code search.invalid_price_range}
     *                 400 拒绝）
     * @param page     分页请求（pageNum/pageSize 归一化：第 1 页起、
     *                 默认 10、上限 100）
     * @return 分页卡片结果（空搜索返回空列表 + total 0，非 null）
     */
    PageResult<ProductCard> search(SearchCriteria criteria, PageQuery page);

    /**
     * 商品搜索（分页，账号形态——写后自读窗口路由）。
     * <p>
     * 账号为当前登录用户（写者本人视角）：落入 3s 写后窗口（账号级
     * lastWrite 标记）时本次查询临时走<b>主库</b>（read-your-writes，
     * 写者可见自己刚写入的内容）；窗口外/Redis 故障降级照常走 PG
     * 读库（replica 通道）。类型路由为主、窗口为次：其余查询场景
     * （订单/详情/购物车等）静态走主库，与本入口无关。
     *
     * @param criteria 检索条件（同 {@link #search(SearchCriteria, PageQuery)}）
     * @param page     分页请求（同 {@link #search(SearchCriteria, PageQuery)}）
     * @param uid      当前账号 ID（写者本人；null = 无窗口判定，走 PG 读库）
     * @return 分页卡片结果（空搜索返回空列表 + total 0，非 null）
     */
    PageResult<ProductCard> search(SearchCriteria criteria, PageQuery page, Long uid);
}