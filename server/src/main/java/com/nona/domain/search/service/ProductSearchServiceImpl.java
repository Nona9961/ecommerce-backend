package com.nona.domain.search.service;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.domain.search.ports.SearchWriteWindow;
import com.nona.domain.search.repo.PrimaryProductSearchViewRepository;
import com.nona.domain.search.repo.ProductSearchViewRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品搜索服务实现（搜索编排；写后窗口路由）。
 * <p>
 * 编排职责：
 * <ol>
 *     <li>参数校验位：价格区间非负/不倒挂（{@code search.invalid_price_range}
 *         400 拒绝）等入参把关；</li>
 *     <li>条件归约：keyword 空白归一、sort 默认值（TIME_DESC）；</li>
 *     <li>仓储编排：读 PG 视图分页查询 + 总数统计（同条件），组装
 *         {@link PageResult}。</li>
 * </ol>
 * 数据源语义：无账号形态（既有 2 参契约）静态走 replica 通道（PG 镜像视图，
 * 白名单装配无运行时路由）；账号形态在写后窗口命中时本次查询
 * 走主库通道（{@link PrimaryProductSearchViewRepository}，read-your-writes），
 * 其余照常走 replica——「类型路由为主、窗口为次」的账号级受控覆盖。
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
     * 搜索主库通道仓储（写后窗口命中时的强一致查询，主库覆盖）。
     */
    private final PrimaryProductSearchViewRepository primaryProductSearchViewRepository;

    /**
     * 写后自读窗口判定端口（3s 业务窗口；Redis 故障降级 false）。
     */
    private final SearchWriteWindow searchWriteWindow;

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
     * {@inheritDoc}
     * <p>
     * 编排语义：校验/归约同等（同 {@link #search(SearchCriteria, PageQuery)}）
     * → 账号级窗口判定（null 账号不判定）→ 窗口内走主库通道（强一致，
     * 写者可见刚写入内容）、否则走 replica 通道；两通道同构查询语义，
     * 路由只换执行通道——「类型路由为主、窗口为次」的账号级受控覆盖；
     * 路由决策唯一出现在本入口。
     */
    @Override
    public PageResult<ProductCard> search(SearchCriteria criteria, PageQuery page, Long uid) {
        validatePriceRange(criteria);
        final SearchCriteria normalized = normalizeKeyword(criteria);
        if (uid != null && searchWriteWindow.isWithinWriteWindow(uid)) {
            final List<ProductCard> records =
                    primaryProductSearchViewRepository.search(normalized, page);
            final long total = primaryProductSearchViewRepository.count(normalized);
            return PageResult.of(records, total, page);
        }
        return search(criteria, page);
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