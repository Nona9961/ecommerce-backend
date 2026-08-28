package com.nona.api.auth;

import com.nona.api.HttpResponse;

/**
 * 认证契约：三端共用的登录/注册接口定义。
 * <p>
 * 服务端实现位于 server 模块；三端 controller 统一实现本接口，路径前缀分别为
 * /mall /seller /admin。凭证校验、账号状态拦截（封禁即拒绝登录）与 JWT 签发
 * 由认证链路统一处理，业务用例不感知 token 机制。
 *
 * @author nona9961
 */
public interface AuthApi {

    /**
     * 登录：校验凭证与账号状态，签发无状态 JWT。
     *
     * @param request 登录请求（用户名/密码/门户）
     * @return 成功时携带 JWT 与商家端店铺列表；凭证错误或账号封禁返回失败响应
     */
    HttpResponse<LoginResponse> login(LoginRequest request);

    /**
     * 注册：创建账号（买家即时生效；商家仅创建账号，开店走入驻审核）。
     *
     * @param request 注册请求（用户名/密码/门户）
     * @return 成功时携带新建账号的用户 ID
     */
    HttpResponse<RegisterResponse> register(RegisterRequest request);
}
