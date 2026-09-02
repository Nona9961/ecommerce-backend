package com.nona.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 契约测试：{@link EcommerceBusinessCode} 域码默认状态映射与兜底链（零 spring 依赖，
 * HTTP 状态码用 int）。
 * <p>
 * 契约要点：
 * <ul>
 *     <li>dotted 小写域码，auth 段四码：{@code auth.unauthorized}→401、{@code auth.forbidden}→403、
 *         {@code auth.bad_credentials}→400、{@code auth.username_conflict}→400</li>
 *     <li>业务域段占位基码：{@code catalog.not_found} / {@code inventory.not_found} /
 *         {@code order.not_found} / {@code payment.not_found} → 404（随各域 WU 扩展，只增不改）</li>
 *     <li>状态解析链：域码命中 → 映射；未命中 → 委托 {@link BusinessCode#defaultStatus}
 *         （generic 码）；完全未知 → 兜底 500</li>
 *     <li>映射 API：{@code int defaultStatus(String code)}——未知码不抛异常（查表语义）</li>
 * </ul>
 * 注：业务码以码值字面量断言（契约冻结的是码值；枚举成员名不属于契约）。
 *
 * @author nona9961
 */
class EcommerceBusinessCodeTest {

    // ---- Happy path ----

    @Test
    @DisplayName("H: auth.unauthorized → 401（未认证）")
    void authUnauthorizedShouldMapTo401() {
        assertThat(EcommerceBusinessCode.defaultStatus("auth.unauthorized")).isEqualTo(401);
    }

    @Test
    @DisplayName("H: auth.forbidden → 403（封禁与权限统一）")
    void authForbiddenShouldMapTo403() {
        assertThat(EcommerceBusinessCode.defaultStatus("auth.forbidden")).isEqualTo(403);
    }

    @Test
    @DisplayName("H: auth.bad_credentials → 400（凭证错误）")
    void authBadCredentialsShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("auth.bad_credentials")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: auth.username_conflict → 400（注册用户名冲突）")
    void authUsernameConflictShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("auth.username_conflict")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: catalog.not_found → 404（商品域占位基码）")
    void catalogNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("catalog.not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: catalog.shop_required → 400（店铺对象缺失）")
    void catalogShopRequiredShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("catalog.shop_required")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: storage.content_type_not_allowed → 400（MIME 白名单拒绝）")
    void storageContentTypeNotAllowedShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("storage.content_type_not_allowed")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: storage.file_too_large → 400（超大小上限）")
    void storageFileTooLargeShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("storage.file_too_large")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: storage.file_empty → 400（空文件）")
    void storageFileEmptyShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("storage.file_empty")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: storage.object_key_invalid → 400（objectKey 非法）")
    void storageObjectKeyInvalidShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("storage.object_key_invalid")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: storage.file_not_found → 404（文件不存在）")
    void storageFileNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("storage.file_not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: inventory.not_found → 404（库存域占位基码）")
    void inventoryNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("inventory.not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: order.not_found → 404（订单域占位基码）")
    void orderNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("order.not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: payment.not_found → 404（支付域占位基码）")
    void paymentNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("payment.not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: identity.onboarding_conflict → 400（入驻申请已存在）")
    void identityOnboardingConflictShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("identity.onboarding_conflict")).isEqualTo(400);
    }

    @Test
    @DisplayName("H: identity.onboarding_not_found → 404（入驻申请不存在）")
    void identityOnboardingNotFoundShouldMapTo404() {
        assertThat(EcommerceBusinessCode.defaultStatus("identity.onboarding_not_found")).isEqualTo(404);
    }

    @Test
    @DisplayName("H: identity.onboarding_state → 400（入驻申请非法状态迁移）")
    void identityOnboardingStateShouldMapTo400() {
        assertThat(EcommerceBusinessCode.defaultStatus("identity.onboarding_state")).isEqualTo(400);
    }

    // ---- Critical path ----

    @Test
    @DisplayName("C: 域码未命中 → 委托 BusinessCode（generic.validation_failed → 400）")
    void unknownDomainCodeShouldDelegateToBusinessCode() {
        assertThat(EcommerceBusinessCode.defaultStatus("generic.validation_failed")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("generic.not_found")).isEqualTo(404);
    }

    // ---- Fail path ----

    @Test
    @DisplayName("F: 完全未知业务码兜底 500，且不抛异常（域码 → generic → 兜底链末端）")
    void fullyUnknownCodeShouldFallBackTo500WithoutThrowing() {
        assertThatCode(() -> EcommerceBusinessCode.defaultStatus("unknown.reason"))
                .doesNotThrowAnyException();
        assertThat(EcommerceBusinessCode.defaultStatus("unknown.reason")).isEqualTo(500);
    }
}
