package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProductEditVersion 记录实体契约测试：版本行字段形态（版本号/快照/
 * 操作人/触发类型/产生时间）、append-only 语义（无变更路径，只读访问器）、
 * 装配守卫（版本号为正/快照与操作人非空/归属商品必填）与触发类型枚举
 * 取值（EDIT/ROLLBACK 本期产生；REVIEW_PASS/REJECT 属后续阶段预留）。
 * <p>
 * 红状态说明：实体为数据载体（装配底座，构造/读取在红阶段实现，本文件
 * 断言为绿底座）；版本链领域语义（保存留痕/版本号递增分配/回滚编排）
 * 为用例层契约声明（抛 UnsupportedOperationException），由
 * ProductVersionUseCaseIntegrationAcTest / ProductRestoreContentUnitTest 以
 * 红因承载。
 */
class ProductEditVersionUnitTest {

    /**
     * 快照 JSON 样例（全量内容形态，字段语义由 ProductSnapshotJson 承载）
     */
    private static final String SNAPSHOT = "{\"name\":\"无线耳机\",\"images\":[],"
            + "\"attributes\":[],\"specTemplate\":null,\"skus\":[]}";

    // ---- Happy path ----

    /**
     * happy：新建版本行字段就位（触发类型 EDIT）、产生时间落库前为 null。
     */
    @Test
    @DisplayName("新建版本记录字段就位且产生时间待审计填充")
    void newVersion_fieldsInPlace() {
        final ProductEditVersion version =
                new ProductEditVersion(1001L, 91001L, 1, SNAPSHOT, "72001",
                        EditVersionTriggerType.EDIT);

        assertThat(version.getId()).isEqualTo(1001L);
        assertThat(version.getProductId()).isEqualTo(91001L);
        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getSnapshotJson()).isEqualTo(SNAPSHOT);
        assertThat(version.getOperator()).isEqualTo("72001");
        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.EDIT);
        assertThat(version.getCreatedAt()).isNull();
        assertThat(version.belongsTo(91001L)).isTrue();
        assertThat(version.belongsTo(99999L)).isFalse();
    }

    /**
     * happy：读回路径构造（产生时间来自审计列）字段完整。
     */
    @Test
    @DisplayName("读回路径构造携带产生时间")
    void loadedVersion_carriesCreatedAt() {
        final LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 10, 30);
        final ProductEditVersion version =
                new ProductEditVersion(1002L, 91001L, 2, SNAPSHOT, "72001",
                        EditVersionTriggerType.ROLLBACK, createdAt);

        assertThat(version.getCreatedAt()).isEqualTo(createdAt);
        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.ROLLBACK);
    }

    /**
     * happy：触发类型枚举含本期两值 + 审核流预留两值（属后续阶段）。
     */
    @Test
    @DisplayName("触发类型枚举含本期两值与审核预留值")
    void triggerType_enumValuesComplete() {
        assertThat(EditVersionTriggerType.values())
                .containsExactly(EditVersionTriggerType.EDIT, EditVersionTriggerType.ROLLBACK,
                        EditVersionTriggerType.REVIEW_PASS, EditVersionTriggerType.REJECT);
    }

    // ---- Critical path ----

    /**
     * critical：版本号从 1 起、多种触发类型均可构造（同一行形态）。
     */
    @Test
    @DisplayName("版本号1起且各触发类型形态一致")
    void versionNo_startsAtOneForAnyTrigger() {
        final ProductEditVersion edit =
                new ProductEditVersion(1003L, 91001L, 1, SNAPSHOT, "72001",
                        EditVersionTriggerType.EDIT);
        final ProductEditVersion rollback =
                new ProductEditVersion(1004L, 91001L, 5, SNAPSHOT, "72002",
                        EditVersionTriggerType.ROLLBACK);

        assertThat(edit.getVersionNo()).isEqualTo(1);
        assertThat(rollback.getVersionNo()).isEqualTo(5);
    }

    // ---- Error path ----

    /**
     * error：版本号非正拒绝（0 与负数无业务意义——版本号恒等式从 1 起）。
     */
    @Test
    @DisplayName("非正版本号拒绝")
    void versionNo_nonPositiveRejected() {
        assertThatThrownBy(() -> new ProductEditVersion(1001L, 91001L, 0,
                SNAPSHOT, "72001", EditVersionTriggerType.EDIT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code()));
        assertThatThrownBy(() -> new ProductEditVersion(1001L, 91001L, -1,
                SNAPSHOT, "72001", EditVersionTriggerType.EDIT))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：快照 JSON 空白拒绝（留痕行必须携带完整内容形态）。
     */
    @Test
    @DisplayName("空白快照拒绝")
    void snapshotJson_blankRejected() {
        assertThatThrownBy(() -> new ProductEditVersion(1001L, 91001L, 1,
                " ", "72001", EditVersionTriggerType.EDIT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code()));
    }

    /**
     * error：操作人空白/触发类型缺失/归属商品缺失拒绝。
     */
    @Test
    @DisplayName("操作人与触发类型与归属缺失拒绝")
    void mandatoryFields_missingRejected() {
        assertThatThrownBy(() -> new ProductEditVersion(1001L, 91001L, 1,
                SNAPSHOT, "", EditVersionTriggerType.EDIT))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ProductEditVersion(1001L, 91001L, 1,
                SNAPSHOT, "72001", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ProductEditVersion(1001L, null, 1,
                SNAPSHOT, "72001", EditVersionTriggerType.EDIT))
                .isInstanceOf(BusinessException.class);
    }
}