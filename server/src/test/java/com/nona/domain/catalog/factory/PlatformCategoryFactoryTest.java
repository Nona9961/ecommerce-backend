package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台分类工厂单元测试：分类创建的入口行为。
 * <p>
 * 覆盖：happy（创建成功、ID 生成、状态定型 ENABLED、排序透传）、
 * error（空名称拒绝、非正排序拒绝——创建路径校验收敛在工厂）。
 */
class PlatformCategoryFactoryTest {

    /**
     * 平台分类工厂
     */
    private final PlatformCategoryFactory factory = new PlatformCategoryFactory();

    /**
     * happy：创建成功——ID 生成、初始状态 ENABLED、排序透传。
     */
    @Test
    @DisplayName("创建分类成功且状态为 ENABLED")
    void create_succeedsWithEnabledStatus() {
        final PlatformCategory category = factory.create("数码", 7);

        assertThat(category.getId()).isNotNull();
        assertThat(category.getName()).isEqualTo("数码");
        assertThat(category.getOrder()).isEqualTo(7);
        assertThat(category.getStatus()).isEqualTo(CategoryStatus.ENABLED);
    }

    /**
     * error：名称为空拒绝创建。
     */
    @Test
    @DisplayName("空名称拒绝创建")
    void create_blankNameRejected() {
        assertThatThrownBy(() -> factory.create("  ", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类名称");
    }

    /**
     * error：非正排序拒绝创建（排序赋值决策在用例层，工厂兜底校验）。
     */
    @Test
    @DisplayName("非正排序拒绝创建")
    void create_nonPositiveOrderRejected() {
        assertThatThrownBy(() -> factory.create("数码", 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排序");
    }
}