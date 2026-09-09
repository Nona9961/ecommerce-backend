package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.SearchCard;
import com.nona.domain.search.ports.ProductSearchService;
import org.springframework.stereotype.Service;

/**
 * 买家商品搜索用例（GET /mall/search 承载，WU-59 冻结——形状钉死
 * 前端 WU-43 约定清单，复用 search 域 ProductSearchService（WU-41，
 * PG 镜像读，只读无写路径）。
 * <p>
 * 编排语义：
 * <ol>
 *     <li><b>检索条件构造</b>：原始 query 参数（keyword/categoryId/
 *         brandId/shopId/minPrice/maxPrice 分/sort 枚举名）在用例内
 *         组装为 SearchCriteria 语义载体——sort 枚举名非法 → 400
 *         fail-closed（SearchSort 无宽松解析，未知值拒绝）；价格区间
 *         校验（负数/倒挂）由服务实现校验位承载
 *         （{@code search.invalid_price_range} 400，契约冻结不移位）；
 *         空白 keyword 等价 null（不限定）；</li>
 *     <li><b>写后自读窗口</b>：uid = 当前登录买家账号 ID（web 层从
 *         认证上下文取；写者本人 3s 窗口主库读，其余走 PG 读库——与
 *         WU-41 冻结契约一致）；</li>
 *     <li><b>形状投影</b>：ProductCard（domain，分）→ SearchCard
 *         （api 契约）逐字段原样投影（minPrice/salesTotal 分直传）；
 *         空搜索返回空列表 + total 0（fail-safe，非 null）。</li>
 * </ol>
 * 装配说明（红阶段）：本类为 {@code @Service} 骨架占位（UOE 方法体——
 * WU-55 五仓储骨架同款：新建类骨架挂注册注解 + 方法体占位，绿阶段只
 * 实现方法体，注解保留）；容器可启动但本用例行为未接线。
 *
 * @author nona9961
 */
@Service
public class ProductSearchUseCase {

    /**
     * 搜索服务（search 域查询契约）
     */
    private final ProductSearchService searchService;

    /**
     * 构造买家商品搜索用例。
     *
     * @param searchService 搜索服务（必填）
     */
    public ProductSearchUseCase(ProductSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 商品搜索（分页，账号形态——写后自读窗口由 uid 路由）。
     *
     * @param keyword    关键词（可空 = 不限定；空白等价 null）
     * @param categoryId 平台一级类目 ID（可空）
     * @param brandId    品牌 ID（可空）
     * @param shopId     店铺 ID（可空）
     * @param minPrice   价格下界（分，含端点；可空 = 不限）
     * @param maxPrice   价格上界（分，含端点；可空 = 不限）
     * @param sort       排序枚举名（可空 = 默认 TIME_DESC；非法值 400）
     * @param page       分页请求（PageQuery 归一化）
     * @param uid        当前登录买家账号 ID（写者本人；必不为 null——
     *                   /mall/** 必有身份）
     * @return 分页卡片结果（SearchCard，金额分）
     */
    public PageResult<SearchCard> search(String keyword, Long categoryId, Long brandId,
                                         Long shopId, Long minPrice, Long maxPrice,
                                         String sort, PageQuery page, Long uid) {
        throw new UnsupportedOperationException(
                "search 未实现：红阶段契约占位，绿阶段实现（条件组装 + 投影）");
    }
}