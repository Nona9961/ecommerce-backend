package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.FavoriteApi;
import com.nona.api.mall.FavoriteItem;
import com.nona.api.mall.FavoriteRequest;
import com.nona.api.mall.FavoriteType;
import com.nona.application.mall.FavoriteUseCase;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家收藏 REST 控制器（/mall/favorites，BUYER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link FavoriteUseCase}；当前买家 ID 从跟踪上下文 取
 * （认证过滤器已写入 JWT 主体，不来自请求体）。收藏与取消收藏共用
 * 请求体形态（目标类型 + 目标 ID），保持契约对称；三个操作均幂等。
 *
 * @author nona9961
 */
@RestController
public class FavoriteController implements FavoriteApi {

    /**
     * 收藏用例
     */
    private final FavoriteUseCase favoriteUseCase;

    /**
     * 请求上下文（取当前登录买家 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造收藏控制器。
     *
     * @param favoriteUseCase 收藏用例
     * @param threadContext   请求上下文
     */
    public FavoriteController(FavoriteUseCase favoriteUseCase, TenantContextAccessor tenantContextAccessor) {
        this.favoriteUseCase = favoriteUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/mall/favorites")
    public HttpResponse<Void> favorite(@Valid @RequestBody FavoriteRequest request) {
        favoriteUseCase.favorite(currentAccountId(), request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/mall/favorites")
    public HttpResponse<Void> unfavorite(@Valid @RequestBody FavoriteRequest request) {
        favoriteUseCase.unfavorite(currentAccountId(), request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查询参数 targetType 为可选过滤（缺省返回全部类型）；非法值显式解析拒绝
     * （400 generic.validation_failed，与请求体校验语义一致）。
     */
    @Override
    @GetMapping("/mall/favorites")
    public HttpResponse<PageResult<FavoriteItem>> list(
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        final FavoriteType type = targetType == null ? null : FavoriteType.fromName(targetType);
        return HttpResponse.ok(favoriteUseCase.list(currentAccountId(), type, new PageQuery(pageNum, pageSize)));
    }

    /**
     * 当前登录买家 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 买家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}