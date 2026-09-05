package com.nona.web.admin;

import com.nona.api.HttpResponse;
import com.nona.api.admin.ApplicationRejectRequest;
import com.nona.api.admin.OnboardingAuditItem;
import com.nona.api.admin.OnboardingReviewApi;
import com.nona.api.common.OnboardingStatus;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.application.admin.OnboardingReviewUseCase;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台入驻审核 REST 控制器（/admin/onboarding，ADMIN 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link OnboardingReviewUseCase}；当前审核人 ID 从跟踪上下文
 * 取（认证过滤器已写入 JWT 主体）。列表状态参数为可选过滤（缺省全部），
 * 非法值显式解析拒绝（400 generic.validation_failed，与请求体校验语义一致）。
 * 审核通过时用例在同事务内完成开店编排（申请迁移 → 建店 → 绑定 → 事件 →
 * 缓存失效）并返回新店铺 ID，本控制器保持薄壳委托。
 *
 * @author nona9961
 */
@RestController
public class OnboardingReviewController implements OnboardingReviewApi {

    /**
     * 平台审核用例
     */
    private final OnboardingReviewUseCase reviewUseCase;

    /**
     * 请求上下文（取当前登录平台运营 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造平台审核控制器。
     *
     * @param reviewUseCase 平台审核用例
     * @param threadContext 请求上下文
     */
    public OnboardingReviewController(OnboardingReviewUseCase reviewUseCase, TenantContextAccessor tenantContextAccessor) {
        this.reviewUseCase = reviewUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查询参数 status 为可选过滤（缺省返回全部）；非法值显式解析拒绝。
     */
    @Override
    @GetMapping("/admin/onboarding")
    public HttpResponse<PageResult<OnboardingAuditItem>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        final OnboardingStatus filter = status == null ? null : OnboardingStatus.fromName(status);
        return HttpResponse.ok(reviewUseCase.list(filter, new PageQuery(pageNum, pageSize)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/onboarding/{applicationId}/approve")
    public HttpResponse<Long> approve(@PathVariable("applicationId") Long applicationId) {
        return HttpResponse.ok(reviewUseCase.approve(applicationId, currentAccountId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/onboarding/{applicationId}/reject")
    public HttpResponse<Void> reject(@PathVariable("applicationId") Long applicationId,
                                     @Valid @RequestBody ApplicationRejectRequest request) {
        reviewUseCase.reject(applicationId, currentAccountId(), request.reason());
        return HttpResponse.ok();
    }

    /**
     * 当前登录平台运营 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 平台运营账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}