package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.SearchApi;
import com.nona.api.mall.SearchCard;
import com.nona.application.mall.ProductSearchUseCase;
import com.nona.inf.context.TenantContextAccessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品搜索 REST 控制器（GET /mall/search，仅买家角色可访问）——WU-59
 * 冻结端点契约，形状钉死前端 WU-43 约定清单（searchApi.ts 查询参数
 * 逐一对应：keyword/categoryId/brandId/shopId/minPrice/maxPrice/sort/
 * pageNum/pageSize——搜索为 GET 无请求体，全部经 query 参数展开）。
 * <p>
 * 控制器保持薄壳：参数透传 + 委托 {@link ProductSearchUseCase}；当前
 * 买家账号 ID 从跟踪上下文取（写后自读窗口路由锚点，/mall/** 必
 * 有身份）。
 *
 * @author nona9961
 */
@RestController
public class SearchController implements SearchApi {

    /**
     * 商品搜索用例
     */
    private final ProductSearchUseCase searchUseCase;

    /**
     * 请求上下文（取当前登录买家 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造商品搜索控制器。
     *
     * @param searchUseCase        商品搜索用例
     * @param tenantContextAccessor 请求上下文
     */
    public SearchController(ProductSearchUseCase searchUseCase,
                            TenantContextAccessor tenantContextAccessor) {
        this.searchUseCase = searchUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}——商品搜索（query 参数展开 + 分页归一化委托用例）。
     */
    @Override
    @GetMapping("/mall/search")
    public HttpResponse<PageResult<SearchCard>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "brandId", required = false) Long brandId,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestParam(value = "minPrice", required = false) Long minPrice,
            @RequestParam(value = "maxPrice", required = false) Long maxPrice,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return HttpResponse.ok(searchUseCase.search(keyword, categoryId, brandId, shopId,
                minPrice, maxPrice, sort, new PageQuery(pageNum, pageSize),
                currentAccountId()));
    }

    /**
     * 当前登录买家账号 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 买家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}