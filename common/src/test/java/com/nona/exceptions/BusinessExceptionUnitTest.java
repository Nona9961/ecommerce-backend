package com.nona.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：{@link BusinessException} 三构造器行为（业务码/状态解析链，零 spring 依赖）。
 * <p>
 * 契约要点：
 * <ul>
 *     <li>message-only：业务码为 null，状态兜底 500</li>
 *     <li>businessCode + message：状态按 {@link EcommerceBusinessCode#defaultStatus(String)}
 *         解析（域码 → generic → 兜底 500）</li>
 *     <li>businessCode + message + 显式状态：显式状态优先于默认映射</li>
 *     <li>message 原样透传（面向 API 消费方）</li>
 * </ul>
 *
 * @author nona9961
 */
class BusinessExceptionUnitTest {

    // ---- Happy path ----

    @Test
    @DisplayName("H: message-only 构造器 → 业务码 null、状态兜底 500、消息透传")
    void messageOnlyShouldFallBackTo500WithNullCode() {
        final BusinessException ex = new BusinessException("something went wrong");

        assertThat(ex.getBusinessCode()).isNull();
        assertThat(ex.getHttpStatus()).isEqualTo(500);
        assertThat(ex.getMessage()).isEqualTo("something went wrong");
    }

    @Test
    @DisplayName("H: businessCode + message → 域码默认映射（auth.unauthorized → 401）")
    void businessCodeShouldResolveDomainMapping() {
        final BusinessException ex = new BusinessException(
                EcommerceBusinessCode.AUTH_UNAUTHORIZED.code(), "unauthorized");

        assertThat(ex.getBusinessCode()).isEqualTo("auth.unauthorized");
        assertThat(ex.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("H: businessCode + message → generic 码委托映射（generic.validation_failed → 400）")
    void businessCodeShouldDelegateGenericMapping() {
        final BusinessException ex = new BusinessException(
                BusinessCode.VALIDATION_FAILED.code(), "illegal args");

        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("H: 显式状态构造器 → 状态优先于默认映射（catalog 码默认 404，显式 409 生效）")
    void explicitStatusShouldWinOverDefaultMapping() {
        final BusinessException ex = new BusinessException(
                "catalog.stock_insufficient", "库存不足", 409);

        assertThat(ex.getBusinessCode()).isEqualTo("catalog.stock_insufficient");
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    // ---- Fail path ----

    @Test
    @DisplayName("F: 未知业务码 + message → 兜底 500（解析链末端，不抛异常）")
    void unknownCodeShouldFallBackTo500() {
        final BusinessException ex = new BusinessException("unknown.reason", "unknown");

        assertThat(ex.getHttpStatus()).isEqualTo(500);
    }
}
