package com.nona.util;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 契约测试：{@link BusinessAssert} 带 businessCode 入口。
 * <p>
 * 契约要点：
 * <ul>
 *     <li>带码入口：businessCode 透传、httpStatus 按 {@link com.nona.exceptions.BusinessCode} 默认映射解析</li>
 *     <li>未登记业务码 fail-closed 兜底 500，不抛异常</li>
 *     <li>message-only 入口行为不变（code=null、httpStatus=500）</li>
 * </ul>
 *
 * @author nona9961
 */
class BusinessAssertUnitTest {

    // ---- Happy path ----

    @Test
    @DisplayName("H: 带码 assertTrue 失败 → businessCode 透传、httpStatus 按映射 400")
    void assertTrueWithCodeShouldCarryCodeAndMappedStatus() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.assertTrue("generic.validation_failed", false, "must pass"));

        assertThat(ex.getBusinessCode()).isEqualTo("generic.validation_failed");
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("H: 带码 assertTrue 条件满足时不抛")
    void assertTrueWithCodeShouldNotThrowWhenConditionHolds() {
        assertDoesNotThrow(() ->
                BusinessAssert.assertTrue("generic.validation_failed", true, "must pass"));
    }

    @Test
    @DisplayName("H: 带码 assertNonNull 失败 → businessCode 透传、状态按映射 404；非空时不抛")
    void assertNonNullWithCodeShouldCarryCodeAndMapStatusWhenNull() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.assertNonNull("generic.not_found", (Object) null, "entity must exist"));

        assertThat(ex.getBusinessCode()).isEqualTo("generic.not_found");
        assertThat(ex.getHttpStatus()).isEqualTo(404);

        assertDoesNotThrow(() ->
                BusinessAssert.assertNonNull("generic.not_found", new Object(), "entity must exist"));
    }

    @Test
    @DisplayName("H: 带码 + {} 占位符：消息格式化仍生效且 code/状态照常")
    void codeAwareFormattingShouldStillFormatTemplate() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.assertTrue("generic.validation_failed", false,
                        "category {} already exists", 42));

        assertThat(ex.getMessage()).isEqualTo("category 42 already exists");
        assertThat(ex.getBusinessCode()).isEqualTo("generic.validation_failed");
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("H: throwBusinessWithCode 直接抛出 → code 透传、占位符格式化、状态映射")
    void throwBusinessWithCodeShouldThrowWithCode() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.throwBusinessWithCode("generic.not_found", "order {} not found", 7));

        assertThat(ex.getMessage()).isEqualTo("order 7 not found");
        assertThat(ex.getBusinessCode()).isEqualTo("generic.not_found");
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("H: generateExByMsgWithCode 直接生成 → 异常携带 code 与格式化消息")
    void generateExByMsgWithCodeShouldReturnCodedException() {
        BusinessException ex = BusinessAssert.generateExByMsgWithCode(
                "generic.validation_failed", "field {} is invalid", "name");

        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("field name is invalid");
        assertThat(ex.getBusinessCode()).isEqualTo("generic.validation_failed");
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    // ---- Critical path ----

    @Test
    @DisplayName("C: 未登记业务码（catalog.unregistered_code）→ code 透传、状态兜底 500")
    void unknownCodeShouldKeepCodeAndFallBackTo500() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.assertTrue("catalog.unregistered_code", false, "duplicate"));

        assertThat(ex.getBusinessCode()).isEqualTo("catalog.unregistered_code");
        assertThat(ex.getHttpStatus()).isEqualTo(500);
    }

    // ---- Fail path ----

    @Test
    @DisplayName("F: message-only 路径行为不变（code=null、httpStatus=500）")
    void messageOnlyPathShouldKeepLegacyShape() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.assertTrue(false, "legacy boom"));

        assertThat(ex.getBusinessCode()).isNull();
        assertThat(ex.getHttpStatus()).isEqualTo(500);
    }
}