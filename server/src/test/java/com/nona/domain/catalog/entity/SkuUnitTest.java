package com.nona.domain.catalog.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SKU 实体行为面契约测试：构造定型（身份/归属/规格派生值/默认状态）、
 * 构造防御（身份三要素必填）与包级变更方法（价格/启用——仅聚合调用）。
 * <p>
 * 覆盖：happy（构造定型、改价/启停生效）、critical（未定价合法态——
 * 变更路径契约）、error（构造防御拒绝缺失要素）。
 * <p>
 * 红状态说明：构造与读取为装配形态（已实现，绿）；变更方法
 * （updatePrice/setEnabled）为契约声明（方法体抛
 * UnsupportedOperationException）——变更类用例红，红因 = 实现缺失。
 */
class SkuUnitTest {

    /**
     * happy：构造定型——身份/归属/规格派生值定型不可变；价格与启用
     * 状态按构造参数落入。
     */
    @Test
    @DisplayName("构造定型身份归属与规格派生值")
    void construct_fieldsFixedAtCreation() {
        final Sku sku = new Sku(11L, 9001L, "spec-hash-a", "颜色:黑,尺寸:L", 1999L, true);

        assertThat(sku.getId()).isEqualTo(11L);
        assertThat(sku.getProductId()).isEqualTo(9001L);
        assertThat(sku.getSpecHash()).isEqualTo("spec-hash-a");
        assertThat(sku.getSpecSummary()).isEqualTo("颜色:黑,尺寸:L");
        assertThat(sku.getPrice()).isEqualTo(1999L);
        assertThat(sku.isEnabled()).isTrue();
    }

    /**
     * critical：未定价为合法形态（price=null 落入，草稿期允许）。
     */
    @Test
    @DisplayName("未定价 SKU 为合法形态")
    void construct_unpricedSkuIsLegal() {
        final Sku sku = new Sku(12L, 9001L, "spec-hash-b", "颜色:白", null, false);

        assertThat(sku.getPrice()).isNull();
        assertThat(sku.isEnabled()).isFalse();
    }

    /**
     * error：构造防御——身份三要素（ID/归属商品/规格摘要）缺失拒绝。
     */
    @Test
    @DisplayName("身份三要素缺失的 SKU 构造拒绝")
    void construct_missingIdentityRejected() {
        assertThatThrownBy(() -> new Sku(null, 9001L, "h", "s", null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sku(11L, null, "h", "s", null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sku(11L, 9001L, " ", "s", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * happy：改价生效（仅聚合路径可调用的包级变更方法契约）。
     */
    @Test
    @DisplayName("更新价格生效")
    void updatePrice_appliesNewPrice() {
        final Sku sku = new Sku(11L, 9001L, "spec-hash-a", "颜色:黑", 1999L, false);

        sku.updatePrice(2599L);

        assertThat(sku.getPrice()).isEqualTo(2599L);
    }

    /**
     * critical：updatePrice 支持 null（清除价格复位未定价）。
     */
    @Test
    @DisplayName("更新价格可清除回未定价")
    void updatePrice_clearsToUnpriced() {
        final Sku sku = new Sku(11L, 9001L, "spec-hash-a", "颜色:黑", 1999L, false);

        sku.updatePrice(null);

        assertThat(sku.getPrice()).isNull();
    }

    /**
     * happy：启停切换生效（仅聚合路径可调用的包级变更方法契约）。
     */
    @Test
    @DisplayName("切换启用状态生效")
    void setEnabled_togglesState() {
        final Sku sku = new Sku(11L, 9001L, "spec-hash-a", "颜色:黑", 1999L, false);

        sku.setEnabled(true);
        assertThat(sku.isEnabled()).isTrue();

        sku.setEnabled(false);
        assertThat(sku.isEnabled()).isFalse();
    }
}