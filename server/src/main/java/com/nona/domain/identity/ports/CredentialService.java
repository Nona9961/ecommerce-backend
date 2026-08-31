package com.nona.domain.identity.ports;

/**
 * 凭证端口（identity 域 ports）：密码 BCrypt 加密与校验。
 * <p>
 * 落本域端口、由基础设施实现（inf.security.BcryptCredentialService）：
 * 密码一律只以摘要形式进入账号聚合与存储，明文不落库、不进入领域对象。
 *
 * @author nona9961
 */
public interface CredentialService {

    /**
     * 加密明文密码为 BCrypt 摘要。
     *
     * @param rawPassword 明文密码
     * @return BCrypt 摘要（含盐）
     */
    String hash(String rawPassword);

    /**
     * 校验明文密码与摘要是否匹配。
     *
     * @param rawPassword    明文密码
     * @param hashedPassword BCrypt 摘要
     * @return 匹配返回 true
     */
    boolean matches(String rawPassword, String hashedPassword);
}
