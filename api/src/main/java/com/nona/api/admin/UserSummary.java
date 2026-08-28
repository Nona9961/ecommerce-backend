package com.nona.api.admin;

/**
 * 用户摘要（平台端用户管理列表项）。
 * <p>
 * 最小字段契约：账号实现落地时按需扩展，字段命名保持 camelCase。
 *
 * @param id       用户 ID
 * @param username 用户名
 * @param status   账号状态（ACTIVE/BANNED 等，随账号域实现补充）
 */
public record UserSummary(
        Long id,
        String username,
        String status
) {
}
