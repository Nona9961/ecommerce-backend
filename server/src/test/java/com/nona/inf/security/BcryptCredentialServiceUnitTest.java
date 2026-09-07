package com.nona.inf.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BCrypt 凭证服务单元测试：加密与校验语义（Phase 1 骨架态，预期红）。
 * <p>
 * 断言对准 BCrypt 契约：摘要非明文、长度 60 含版本前缀；正确密码匹配、
 * 错误密码不匹配；摘要非法时校验返回 false 而非抛出。
 */
class BcryptCredentialServiceUnitTest {

    /**
     * 被测凭证服务（骨架：Phase 2 以 BCryptPasswordEncoder 落地）
     */
    private final BcryptCredentialService service = new BcryptCredentialService();

    /**
     * 加密：明文密码转为 BCrypt 摘要（非明文、长度 60、含版本前缀）。
     */
    @Test
    void hash_producesBcryptDigest() {
        final String hash = service.hash("password123");
        assertThat(hash)
                .isNotEqualTo("password123")
                .hasSize(60)
                .startsWith("$2");
    }

    /**
     * 加密：同一密码两次加密摘要不同（BCrypt 随机盐）。
     */
    @Test
    void hash_samePasswordTwice_producesDifferentDigests() {
        final String first = service.hash("password123");
        final String second = service.hash("password123");
        assertThat(first).isNotEqualTo(second);
    }

    /**
     * 校验：正确明文密码匹配摘要。
     */
    @Test
    void matches_correctPassword_returnsTrue() {
        final String hash = service.hash("password123");
        assertThat(service.matches("password123", hash)).isTrue();
    }

    /**
     * 校验：错误明文密码不匹配摘要。
     */
    @Test
    void matches_wrongPassword_returnsFalse() {
        final String hash = service.hash("password123");
        assertThat(service.matches("wrong-password", hash)).isFalse();
    }

    /**
     * 校验：摘要形态非法（非 BCrypt）返回 false 而非抛出（防信息泄露与异常路径）。
     */
    @Test
    void matches_illegalHash_returnsFalse() {
        assertThat(service.matches("password123", "not-a-bcrypt-hash")).isFalse();
    }
}
