package com.nona.web;

import com.nona.api.HttpResponse;
import com.nona.api.auth.AuthApi;
import com.nona.api.auth.LoginRequest;
import com.nona.api.auth.LoginResponse;
import com.nona.api.auth.RegisterRequest;
import com.nona.api.auth.RegisterResponse;
import com.nona.application.support.AuthUseCase;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST 控制器：登录 / 注册 / 登出三端共用端点（/auth/*，公开路径由安全链放行）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link AuthUseCase}，不承载业务逻辑；
 * 登出的当前用户 ID 从跟踪上下文 取（登出路径已认证）。
 *
 * @author nona9961
 */
@RestController
public class AuthController implements AuthApi {

    /**
     * 认证用例
     */
    private final AuthUseCase authUseCase;

    /**
     * 请求上下文（取当前登录用户 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造认证控制器。
     *
     * @param authUseCase   认证用例
     * @param threadContext 请求上下文
     */
    public AuthController(AuthUseCase authUseCase, TenantContextAccessor tenantContextAccessor) {
        this.authUseCase = authUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/auth/register")
    public HttpResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return HttpResponse.ok(authUseCase.register(request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/auth/login")
    public HttpResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return HttpResponse.ok(authUseCase.login(request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/auth/logout")
    public HttpResponse<Void> logout() {
        authUseCase.logout(currentAccountId());
        return HttpResponse.ok();
    }

    /**
     * 当前登录用户 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 用户账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}
