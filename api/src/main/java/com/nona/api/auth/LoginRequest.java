package com.nona.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登录请求体：三端共用契约。
 *
 * @param username 用户名（买家/商家同表按 type 分开命名空间，同 type 内唯一）
 * @param password 明文密码（传输层加密由 HTTPS 保证，服务端校验后即弃）
 * @param portal   登录门户，决定账号 type（MALL→BUYER、SELLER→SELLER；ADMIN 无落点）
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotNull Portal portal
) {
}
