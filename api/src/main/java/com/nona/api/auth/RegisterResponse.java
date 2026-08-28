package com.nona.api.auth;

/**
 * 注册响应体：三端共用契约。
 *
 * @param userId 新建账号的用户 ID
 */
public record RegisterResponse(
        Long userId
) {
}
