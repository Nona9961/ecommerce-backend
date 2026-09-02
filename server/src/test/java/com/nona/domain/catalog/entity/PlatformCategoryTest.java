package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台分类聚合根单元测试：分类改名/排序/状态迁移的领域行为。
 * <p>
 * 覆盖：happy（改名生效、重排序生效、禁用/启用迁移）、critical（重复禁用幂等、
 * 重复启用幂等）、error（空名称拒绝改名、非正排序拒绝）。
 * 名称唯一性为仓储/用例层守卫（跨行不变量），不在本聚合内验证。
 */
class PlatformCategoryTest {

    /**
     * happy：改名后名称更新，排序与状态不变。
     */
    @Test
    @DisplayName("改名生效且排序状态不变")
    void rename_updatesNameKeepsOrderAndStatus() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 2, CategoryStatus.ENABLED);
        category.rename("消费电子");

        assertThat(category.getName()).isEqualTo("消费电子");
        assertThat(category.getOrder()).isEqualTo(2);
        assertThat(category.getStatus()).isEqualTo(CategoryStatus.ENABLED);
    }

    /**
     * happy：重排序后排序更新，名称与状态不变。
     */
    @Test
    @DisplayName("重排序生效且名称状态不变")
    void reorder_updatesOrderKeepsNameAndStatus() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 2, CategoryStatus.ENABLED);
        category.reorder(5);

        assertThat(category.getOrder()).isEqualTo(5);
        assertThat(category.getName()).isEqualTo("数码");
        assertThat(category.getStatus()).isEqualTo(CategoryStatus.ENABLED);
    }

    /**
     * happy：禁用迁移至 DISABLED（行保留，软删语义）。
     */
    @Test
    @DisplayName("禁用迁移至 DISABLED")
    void disable_movesToDisabled() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 1, CategoryStatus.ENABLED);
        category.disable();

        assertThat(category.getStatus()).isEqualTo(CategoryStatus.DISABLED);
    }

    /**
     * critical：重复禁用幂等（已禁用再禁用保持 DISABLED）。
     */
    @Test
    @DisplayName("重复禁用幂等")
    void disable_twiceIdempotent() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 1, CategoryStatus.DISABLED);
        category.disable();
        category.disable();

        assertThat(category.getStatus()).isEqualTo(CategoryStatus.DISABLED);
    }

    /**
     * happy：启用迁移至 ENABLED（禁用态恢复）。
     */
    @Test
    @DisplayName("启用迁移至 ENABLED")
    void enable_movesToEnabled() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 1, CategoryStatus.DISABLED);
        category.enable();

        assertThat(category.getStatus()).isEqualTo(CategoryStatus.ENABLED);
    }

    /**
     * critical：重复启用幂等（已启用再启用保持 ENABLED）。
     */
    @Test
    @DisplayName("重复启用幂等")
    void enable_twiceIdempotent() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 1, CategoryStatus.ENABLED);
        category.enable();
        category.enable();

        assertThat(category.getStatus()).isEqualTo(CategoryStatus.ENABLED);
    }

    /**
     * error：改名为空拒绝（不变量收敛在聚合方法内）。
     */
    @Test
    @DisplayName("空名称拒绝改名")
    void rename_blankNameRejected() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 1, CategoryStatus.ENABLED);
        assertThatThrownBy(() -> category.rename("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类名称");
        assertThat(category.getName()).isEqualTo("数码");
    }

    /**
     * error：非正排序拒绝重排序（不变量收敛在聚合方法内）。
     */
    @Test
    @DisplayName("非正排序拒绝重排序")
    void reorder_nonPositiveRejected() {
        final PlatformCategory category = new PlatformCategory(1001L, "数码", 3, CategoryStatus.ENABLED);
        assertThatThrownBy(() -> category.reorder(0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排序");
        assertThat(category.getOrder()).isEqualTo(3);
    }
}