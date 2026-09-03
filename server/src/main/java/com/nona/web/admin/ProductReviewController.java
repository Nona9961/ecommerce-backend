package com.nona.web.admin;

import com.nona.api.HttpResponse;
import com.nona.api.admin.ProductRejectRequest;
import com.nona.api.admin.ProductReviewApi;
import com.nona.api.admin.ProductReviewItem;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.common.ProductLifecycleStatus;
import com.nona.application.admin.ProductReviewUseCase;
import com.nona.inf.context.ThreadContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台商品审核 REST 控制器（/admin/products…，ADMIN 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link ProductReviewUseCase}；当前审核人 ID 从 {@link ThreadContext} 取
 * （认证过滤器已写入 JWT 主体）。列表状态参数为可选过滤（缺省全部），
 * 非法值显式解析拒绝（400 generic.validation_failed，与请求体校验语义
 * 一致）。审核动作（通过/驳回）由用例在提权事务内完成（商品数据归店铺
 * 租户，平台视角跨店铺全集——读放行/写放行只出现在应用层用例）。
 *
 * @author nona9961
 */
@RestController
public class ProductReviewController implements ProductReviewApi {

    /**
     * 平台商品审核用例
     */
    private final ProductReviewUseCase reviewUseCase;

    /**
     * 请求上下文（取当前登录平台运营 ID）
     */
    private final ThreadContext threadContext;

    /**
     * 构造平台商品审核控制器。
     *
     * @param reviewUseCase 平台商品审核用例
     * @param threadContext 请求上下文
     */
    public ProductReviewController(ProductReviewUseCase reviewUseCase, ThreadContext threadContext) {
        this.reviewUseCase = reviewUseCase;
        this.threadContext = threadContext;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查询参数 status 为可选过滤（缺省返回全部）；非法值显式解析拒绝。
     * 平台视角跨店铺全集（跨租户读放行在用例方法）。
     */
    @Override
    @GetMapping("/admin/products")
    public HttpResponse<PageResult<ProductReviewItem>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        final ProductLifecycleStatus filter =
                status == null ? null : ProductLifecycleStatus.fromName(status);
        return HttpResponse.ok(reviewUseCase.list(filter, new PageQuery(pageNum, pageSize)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/products/{productId}/approve")
    public HttpResponse<Void> approve(@PathVariable("productId") Long productId) {
        reviewUseCase.approve(productId, currentReviewerId());
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/products/{productId}/reject")
    public HttpResponse<Void> reject(@PathVariable("productId") Long productId,
                                     @Valid @RequestBody ProductRejectRequest request) {
        reviewUseCase.reject(productId, request.reason(), currentReviewerId());
        return HttpResponse.ok();
    }

    /**
     * 当前登录平台运营 ID（认证过滤器写入 ThreadContext 的身份）。
     *
     * @return 平台运营账号 ID
     */
    private Long currentReviewerId() {
        return Long.valueOf(threadContext.getIdentity());
    }
}