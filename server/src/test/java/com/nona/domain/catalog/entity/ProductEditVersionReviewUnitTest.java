package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核结论版本行契约测试（红阶段）：审核结论（通过/驳回）落版本行的
 * 构造形态——驳回原因随行承载（商家据此修改重提），原因与触发类型一致
 * 性由构造路径守卫。
 * <p>
 * 红状态说明：审核结论构造路径为设计契约（方法体抛
 * UnsupportedOperationException）——本文件全部用例红，红因 = 实现缺失；
 * 既有编辑/回滚构造路径为绿底座（不受影响）。
 *
 * @author nona9961
 */
class ProductEditVersionReviewUnitTest {

    /**
     * 快照 JSON 样例（驳回/通过时刻生效内容形态）
     */
    private static final String SNAPSHOT = "{\"name\":\"在售商品\",\"images\":[],"
            + "\"attributes\":[],\"specTemplate\":null,\"skus\":[]}";

    // ---- Happy path ----

    /**
     * happy：审核通过结论行（REVIEW_PASS）字段就位——审核人 = 操作人、
     * 快照 = 通过后的生效内容、无驳回原因。
     */
    @Test
    @DisplayName("审核通过结论行字段就位")
    void reviewPass_conclusionFieldsInPlace() {
        final ProductEditVersion version = new ProductEditVersion(
                1001L, 91001L, 3, SNAPSHOT, "9001", EditVersionTriggerType.REVIEW_PASS, (String) null);

        assertThat(version.getId()).isEqualTo(1001L);
        assertThat(version.getProductId()).isEqualTo(91001L);
        assertThat(version.getVersionNo()).isEqualTo(3);
        assertThat(version.getSnapshotJson()).isEqualTo(SNAPSHOT);
        assertThat(version.getOperator()).isEqualTo("9001");
        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.REVIEW_PASS);
        assertThat(version.getReviewReason()).isNull();
    }

    /**
     * happy：审核驳回结论行（REJECT）承载驳回原因。
     */
    @Test
    @DisplayName("审核驳回结论行承载驳回原因")
    void reject_reasonCarried() {
        final ProductEditVersion version = new ProductEditVersion(
                1002L, 91001L, 4, SNAPSHOT, "9001", EditVersionTriggerType.REJECT, "图片侵权");

        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.REJECT);
        assertThat(version.getReviewReason()).isEqualTo("图片侵权");
    }

    // ---- Critical path ----

    /**
     * critical：审核结论行读回路径（产生时间来自审计列）形态完整。
     */
    @Test
    @DisplayName("审核结论行读回路径携带产生时间")
    void reviewConclusion_loadedCarriesCreatedAt() {
        final ProductEditVersion version = new ProductEditVersion(
                1003L, 91001L, 5, SNAPSHOT, "9001",
                EditVersionTriggerType.REJECT, "规格缺失", java.time.LocalDateTime.of(2026, 9, 3, 12, 0));

        assertThat(version.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 3, 12, 0));
        assertThat(version.getReviewReason()).isEqualTo("规格缺失");
    }

    // ---- Error path ----

    /**
     * error：驳回结论行必须携带原因（无原因驳回无业务意义）。
     */
    @Test
    @DisplayName("驳回结论行原因必填")
    void reject_reasonBlank_rejected() {
        assertThatThrownBy(() -> new ProductEditVersion(
                1001L, 91001L, 3, SNAPSHOT, "9001", EditVersionTriggerType.REJECT, (String) null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_REJECT_REASON_BLANK.code()));
        assertThatThrownBy(() -> new ProductEditVersion(
                1001L, 91001L, 3, SNAPSHOT, "9001", EditVersionTriggerType.REJECT, " "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_REJECT_REASON_BLANK.code()));
    }

    /**
     * error：非驳回触发类型携带原因拒绝（原因与触发类型互斥绑定——
     * 通过结论/编辑留痕行不得混入驳回原因）。
     */
    @Test
    @DisplayName("非驳回行携带原因拒绝")
    void nonReject_withReason_rejected() {
        assertThatThrownBy(() -> new ProductEditVersion(
                1001L, 91001L, 3, SNAPSHOT, "9001", EditVersionTriggerType.REVIEW_PASS, "原因"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code()));
        assertThatThrownBy(() -> new ProductEditVersion(
                1001L, 91001L, 3, SNAPSHOT, "9001", EditVersionTriggerType.EDIT, "原因"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code()));
    }
}