package com.nona.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 注册请求体：三端共用契约。
 * <p>
 * 买家注册即时生效；商家注册仅创建商家账号，开店需另行提交入驻申请并经平台审核。
 *
 * @param username 用户名（同门户命名空间内唯一）
 * @param password 明文密码（服务端 BCrypt 加密存储，不落明文）
 * @param portal   注册门户，决定写入哪张账号表
 */
public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotNull Portal portal
) {
}
