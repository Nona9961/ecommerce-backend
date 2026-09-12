package com.nona.inf.security;

import java.util.Optional;

/**
 * 账号状态 DB SPI（端口）：认证过滤器缓存 miss 时的回填查询。
 * <p>
 * 由身份域落地（账号表 + JPA 查询）；测试用内存实现替代。
 * 查询语义 = 权威源快照：返回 {@link AccountStatus} 与角色列表，
 * 平台封禁改库后即使缓存未清理，miss 路径也会立即读到 BANNED。
 * @author nona9961
 */
public interface AccountStatusProvider {

    /**
     * 按 uid 加载用户上下文（状态 + 角色）。
     *
     * @param uid 用户 ID
     * @return 用户上下文；账号不存在返回空
     */
    Optional<AuthUserContext> loadUserContext(Long uid);
}