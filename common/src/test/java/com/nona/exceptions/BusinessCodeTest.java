package com.nona.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 契约测试：{@link BusinessCode} 通用码默认状态映射（与脚手架版本契约同构，零 spring 依赖，
 * HTTP 状态码用 int）。
 * <p>
 * 契约要点：
 * <ul>
 *     <li>dotted 小写业务码，通用基础集：{@code generic.internal_error}→500、
 *         {@code generic.validation_failed}→400、{@code generic.not_found}→404</li>
 *     <li>映射 API：{@code int defaultStatus(String code)}——已知码→映射状态，未知码→默认 500</li>
 * </ul>
 * 注：业务码以码值字面量断言（契约冻结的是码值；枚举成员名不属于契约）。
 *
 * @author nona9961
 */
class BusinessCodeTest {

    // ---- Happy path ----

    @Test
    @DisplayName("H: generic.internal_error → 500")
    void internalErrorShouldMapTo500() {
        assertThat(BusinessCode.defaultStatus("generic.internal_error")).isEqualTo(500);
    }

    @Test
    @DisplayName("H: generic.validation_failed → 400")
    void validationFailedShouldMapTo400() {
        assertThat(BusinessCode.defaultStatus("generic.validation_failed")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: generic.not_found → 404")
    void notFoundShouldMapTo404() {
        assertThat(BusinessCode.defaultStatus("generic.not_found")).isEqualTo(404);
    }

    // ---- Critical path ----

    @Test
    @DisplayName("C: 未知业务码兜底 500，且不抛异常（映射 API 为查表而非异常路径）")
    void unknownCodeShouldFallBackTo500WithoutThrowing() {
        assertThatCode(() -> BusinessCode.defaultStatus("unknown.reason"))
                .doesNotThrowAnyException();
        assertThat(BusinessCode.defaultStatus("unknown.reason")).isEqualTo(500);
    }
}
