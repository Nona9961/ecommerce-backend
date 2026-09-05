package com.nona.domain.search.service;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.domain.search.repo.ProductSearchViewRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品搜索服务实现（WU-41 一期，设计契约=搜索编排）。
 * <p>
 * 编排职责：
 * <ol>
 *     <li>参数校验位：价格区间非负/不倒挂（{@code search.invalid_price_range}
 *         400 拒绝）等入参把关；</li>
 *     <li>条件归约：keyword 空白归一、sort 默认值（TIME_DESC）；</li>
 *     <li>仓储编排：读 PG 视图分页查询 + 总数统计（同条件），组装
 *         {@link PageResult}。</li>
 * </ol>
 * 数据源语义：本实现不直接触达任何数据源——读路径经搜索读模型仓储
 * 走 replica（PG 镜像视图），静态装配白名单，无运行时路由。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    /**
     * 搜索读模型仓储（PG 镜像视图读取，replica 数据源）。
     */
    private final ProductSearchViewRepository productSearchViewRepository;

    /**
     * {@inheritDoc}
     * <p>
     * 编排语义：价格区间校验（非负 + 不倒挂，违者
     * {@code search.invalid_price_range} 400）→ 条件归约（空白关键词
     * 归一为 null 不限定、排序缺省 TIME_DESC）→ 同条件分页查询 + 总数
     * 统计 → {@link PageResult} 组装（页码/条数回显请求值）。
     */
    @Override
    public PageResult<ProductCard> search(SearchCriteria criteria, PageQuery page) {
        validatePriceRange(criteria);
        final SearchCriteria normalized = normalizeKeyword(criteria);
        final List<ProductCard> records = productSearchViewRepository.search(normalized, page);
        final long total = productSearchViewRepository.count(normalized);
        return PageResult.of(records, total, page);
    }

    /**
     * 价格区间合法性校验：两端均非负；两者齐备时上界不小于下界。
     *
     * @param criteria 检索条件
     */
    private static void validatePriceRange(SearchCriteria criteria) {
        final Long minPrice = criteria.minPrice();
        final Long maxPrice = criteria.maxPrice();
        if (minPrice != null && minPrice < 0L) {
            BusinessAssert.throwBusinessWithCode(
                    EcommerceBusinessCode.SEARCH_INVALID_PRICE_RANGE.code(),
                    "搜索价格下界不能为负");
        }
        if (maxPrice != null && maxPrice < 0L) {
            BusinessAssert.throwBusinessWithCode(
                    EcommerceBusinessCode.SEARCH_INVALID_PRICE_RANGE.code(),
                    "搜索价格上界不能为负");
        }
        if (minPrice != null && maxPrice != null && maxPrice < minPrice) {
            BusinessAssert.throwBusinessWithCode(
                    EcommerceBusinessCode.SEARCH_INVALID_PRICE_RANGE.code(),
                    "搜索价格区间倒挂（上界小于下界）");
        }
    }

    /**
     * 关键词归约：空白关键词归一为 null（不限定），排序缺省 TIME_DESC。
     *
     * @param criteria 检索条件（校验后）
     * @return 归约后的检索条件（record 不可变，新实例）
     */
    private static SearchCriteria normalizeKeyword(SearchCriteria criteria) {
        final String keyword = criteria.keyword();
        final String trimmed = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        final SearchSort sort = criteria.sort() == null ? SearchSort.TIME_DESC : criteria.sort();
        return new SearchCriteria(
                trimmed,
                criteria.categoryId(),
                criteria.brandId(),
                criteria.shopId(),
                criteria.minPrice(),
                criteria.maxPrice(),
                sort);
    }
}