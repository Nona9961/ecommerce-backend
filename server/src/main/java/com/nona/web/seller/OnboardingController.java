package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.seller.OnboardingApi;
import com.nona.api.seller.OnboardingApplicationRequest;
import com.nona.api.seller.OnboardingApplicationResponse;
import com.nona.application.seller.OnboardingUseCase;
import com.nona.inf.context.ThreadContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家入驻申请 REST 控制器（/seller/onboarding，SELLER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link OnboardingUseCase}；
 * 当前商家 ID 从 {@link ThreadContext} 取（认证过滤器已写入 JWT 主体，
 * 不来自请求体）。提交/查看/编辑/重提四个端点与商家入驻验收场景
 * （提交进入待审、状态可见、驳回原因可见可修改重提）一一对应。
 *
 * @author nona9961
 */
@RestController
public class OnboardingController implements OnboardingApi {

    /**
     * 入驻申请用例
     */
    private final OnboardingUseCase onboardingUseCase;

    /**
     * 请求上下文（取当前登录商家 ID）
     */
    private final ThreadContext threadContext;

    /**
     * 构造入驻申请控制器。
     *
     * @param onboardingUseCase 入驻申请用例
     * @param threadContext     请求上下文
     */
    public OnboardingController(OnboardingUseCase onboardingUseCase, ThreadContext threadContext) {
        this.onboardingUseCase = onboardingUseCase;
        this.threadContext = threadContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/onboarding")
    public HttpResponse<OnboardingApplicationResponse> submit(@Valid @RequestBody OnboardingApplicationRequest request) {
        return HttpResponse.ok(onboardingUseCase.submit(currentAccountId(), request));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 未提交时 data 为 null（200），前端展示「未提交」。
     */
    @Override
    @GetMapping("/seller/onboarding")
    public HttpResponse<OnboardingApplicationResponse> getMyApplication() {
        return HttpResponse.ok(onboardingUseCase.getMyApplication(currentAccountId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/onboarding/{applicationId}")
    public HttpResponse<OnboardingApplicationResponse> update(
            @PathVariable("applicationId") Long applicationId, @Valid @RequestBody OnboardingApplicationRequest request) {
        return HttpResponse.ok(onboardingUseCase.update(currentAccountId(), applicationId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/onboarding/{applicationId}/resubmit")
    public HttpResponse<OnboardingApplicationResponse> resubmit(
            @PathVariable("applicationId") Long applicationId, @Valid @RequestBody OnboardingApplicationRequest request) {
        return HttpResponse.ok(onboardingUseCase.resubmit(currentAccountId(), applicationId, request));
    }

    /**
     * 当前登录商家 ID（认证过滤器写入 ThreadContext 的身份）。
     *
     * @return 商家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(threadContext.getIdentity());
    }
}