package com.nona.domain.catalog.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编辑分流判定契约测试（红阶段）：字段级审核白名单——交易敏感字段集
 * {标题, 平台类目, 品牌, SKU 价格, SKU 规格构成} 与增删 SKU 集合 → 转
 * 待审核；展示类字段 {描述, 详情图, 自定义属性}（含 SKU 启停等集外
 * 字段）→ 直接生效；无变更 → 不路由。
 * <p>
 * 红状态说明：判定为净化函数契约（方法体抛
 * UnsupportedOperationException）——本文件全部用例红，红因 = 实现缺失。
 *
 * @author nona9961
 */
class ProductEditSensitivityTest {

    // ---- Happy path ----

    /**
     * happy：单一敏感字段变更即转待审核——标题（name）。
     */
    @Test
    @DisplayName("标题变更判定敏感")
    void nameChange_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of("name"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：平台类目变更判定敏感。
     */
    @Test
    @DisplayName("平台类目变更判定敏感")
    void categoryChange_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of("categoryId"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：品牌变更判定敏感。
     */
    @Test
    @DisplayName("品牌变更判定敏感")
    void brandChange_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of("brandId"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：SKU 价格变更（集合内叶子路径）判定敏感。
     */
    @Test
    @DisplayName("SKU价格变更判定敏感")
    void skuPriceChange_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of("skus[123].price"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：SKU 规格构成变更（组合摘要）判定敏感。
     */
    @Test
    @DisplayName("SKU规格构成变更判定敏感")
    void skuSpecChange_sensitive() {
        assertThat(Product.classifyEditSensitivity(
                Set.of("skus[123].specHash", "skus[456].specSummary"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：SKU 集合增删（规格构成变化）判定敏感。
     */
    @Test
    @DisplayName("SKU集合增删判定敏感")
    void skuCollectionMutation_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of(), Set.of("skus")))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * happy：展示类字段（描述/详情图/自定义属性）直接生效免审。
     */
    @Test
    @DisplayName("展示字段变更判定直改")
    void displayFields_displayOnly() {
        assertThat(Product.classifyEditSensitivity(Set.of("description"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
        assertThat(Product.classifyEditSensitivity(Set.of("images[11].url"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
        assertThat(Product.classifyEditSensitivity(Set.of("images[11].primary"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
        assertThat(Product.classifyEditSensitivity(Set.of("attributes[21].key"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
        assertThat(Product.classifyEditSensitivity(Set.of("attributes[21].value"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
    }

    /**
     * happy：SKU 启停（集外字段，不影响交易要素）按展示类直改免审。
     */
    @Test
    @DisplayName("SKU启停变更判定直改")
    void skuEnabledChange_displayOnly() {
        assertThat(Product.classifyEditSensitivity(Set.of("skus[123].enabled"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
    }

    // ---- Critical path ----

    /**
     * critical：混合编辑含敏感字段即整体转待审核（一次保存一个语义单元）。
     */
    @Test
    @DisplayName("混合编辑命中任一敏感字段即转待审")
    void mixedEdit_hitsSensitive_sensitive() {
        assertThat(Product.classifyEditSensitivity(Set.of("description", "name"), Set.of()))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }

    /**
     * critical：多展示字段共同变更仍直改。
     */
    @Test
    @DisplayName("多展示字段共同变更仍直改")
    void multiDisplayFields_displayOnly() {
        assertThat(Product.classifyEditSensitivity(
                Set.of("description", "images[11].url", "attributes[21].value"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
    }

    /**
     * critical：未知路径（非既有字段）保守按展示类处理（不阻断保存）。
     */
    @Test
    @DisplayName("未知字段路径按展示类处理")
    void unknownPath_displayOnly() {
        assertThat(Product.classifyEditSensitivity(Set.of("otherField"), Set.of()))
                .isEqualTo(EditSensitivity.DISPLAY_ONLY);
    }

    // ---- Error path ----

    /**
     * error：无变更（空集合）不路由——与既有「变更集为空不插版本行」语义
     * 对齐（空保存不落库不分流）。
     */
    @Test
    @DisplayName("无变更不路由")
    void noChanges_none() {
        assertThat(Product.classifyEditSensitivity(Set.of(), Set.of()))
                .isEqualTo(EditSensitivity.NONE);
    }

    /**
     * error：null 输入按空集防御处理（不抛异常）。
     */
    @Test
    @DisplayName("null输入防御为空集")
    void nullInputs_none() {
        assertThat(Product.classifyEditSensitivity(null, null))
                .isEqualTo(EditSensitivity.NONE);
        assertThat(Product.classifyEditSensitivity(null, Set.of("skus")))
                .isEqualTo(EditSensitivity.SENSITIVE);
        assertThat(Product.classifyEditSensitivity(Set.of("name"), null))
                .isEqualTo(EditSensitivity.SENSITIVE);
    }
}