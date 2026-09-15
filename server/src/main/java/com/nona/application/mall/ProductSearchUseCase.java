package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.SearchCard;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 买家商品搜索用例（GET /mall/search 承载，端点契约冻结——形状钉死
 * 前端约定，复用 search 域 ProductSearchService（PG 镜像读，
 * 只读无写路径）。
 * <p>
 * 编排语义：
 * <ol>
 *     <li><b>检索条件构造</b>：原始 query 参数（keyword/categoryId/
 *         brandId/shopId/minPrice/maxPrice 分/sort 枚举名）在用例内
 *         组装为 SearchCriteria 语义载体——sort 枚举名非法 → 400
 *         fail-closed（SearchSort 无宽松解析，未知值拒绝，通用校验码
 *         {@code generic.validation_failed}）；价格区间校验（负数/倒挂）
 *         由服务实现校验位承载（{@code search.invalid_price_range} 400，
 *         契约冻结不移位）；空白 keyword 等价 null（不限定，服务侧归约）；</li>
 *     <li><b>写后自读窗口</b>：uid = 当前登录买家账号 ID（web 层从
 *         认证上下文取；写者本人 3s 窗口主库读，其余走 PG 读库——与
 *         冻结契约一致）；</li>
 *     <li><b>形状投影</b>：ProductCard（domain，分）→ SearchCard
 *         （api 契约）逐字段原样投影（minPrice/salesTotal 分直传）；
 *         空搜索返回空列表 + total 0（fail-safe，非 null）。</li>
 * </ol>
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
        final SearchCriteria criteria = new SearchCriteria(keyword, categoryId, brandId,
                shopId, minPrice, maxPrice, parseSort(sort));
        final PageResult<ProductCard> result = searchService.search(criteria, page, uid);
        final List<SearchCard> cards = result.records().stream()
                .map(card -> new SearchCard(card.productId(), card.name(),
                        card.coverImageUrl(), card.minPrice(), card.salesTotal(),
                        card.shopId(), card.shopName(), card.brandName()))
                .toList();
        return PageResult.of(cards, result.total(), page);
    }

    /**
     * 排序枚举名解析（fail-closed）：空白等价 null（默认 TIME_DESC，
     * 服务侧归约）；未知值 400 拒绝——前端 sort 白名单与后端值域同源，
     * 未知名 = 契约漂移早暴露（SearchSort 无宽松解析，白名单即防线）。
     *
     * @param sort 排序枚举名（可空）
     * @return 排序枚举；null 输入返回 null（服务侧默认 TIME_DESC）
     */
    private static SearchSort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        for (SearchSort candidate : SearchSort.values()) {
            if (candidate.name().equals(sort)) {
                return candidate;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法排序枚举名", 400);
    }
}