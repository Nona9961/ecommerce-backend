package com.nona.inf.security;

import com.nona.domain.identity.ports.CredentialService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 凭证服务：{@link CredentialService} 的基础设施实现。
 * <p>
 * 以 Spring Security 的 {@link BCryptPasswordEncoder} 落地：加密带随机盐
 * （摘要长度 60，含版本前缀），校验恒时比较；摘要形态非法（非 BCrypt）时
 * 校验返回 false 而非抛出——与登录「失败统一消息」语义一致，防信息泄露。
 * 本类不持有任何状态。
 *
 * @author nona9961
 */
@Component
public class BcryptCredentialService implements CredentialService {

    /**
     * BCrypt 编码器（默认强度 10，无状态）
     */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * {@inheritDoc}
     */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 摘要形态非法时返回 false，不抛出。
     */
    @Override
    public boolean matches(String rawPassword, String hashedPassword) {
        try {
            return encoder.matches(rawPassword, hashedPassword);
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }
}
