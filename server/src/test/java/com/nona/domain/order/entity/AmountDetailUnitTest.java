package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金额明细单元测试：金额细分——商品总额/运费/实付字段读取、金额
 * 恒等式（实付 == 商品总额 + 运费 - 优惠）与非负守卫、discount 预留位
 * 契约（当前恒 0、VO 层不拒非零），以及快照冻结语义（构造后不可变）。
 *
 * @author nona9961
 */
class AmountDetailUnitTest {

    /**
     * happy：完整金额明细构造与字段读取（正常下单项：商品 1000 分 + 运费 120 分）。
     */
    @Test
    @DisplayName("完整金额明细构造与字段读取")
    void detail_withAllFields_accessible() {
        final AmountDetail detail = new AmountDetail(1000L, 120L, 0L, 1120L);

        assertThat(detail.getGoodsAmount()).isEqualTo(1000L);
        assertThat(detail.getFreightAmount()).isEqualTo(120L);
        assertThat(detail.getDiscount()).isZero();
        assertThat(detail.getPaidAmount()).isEqualTo(1120L);
    }

    /**
     * happy：全零金额明细可构造（免邮赠品单形态：商品额 0、运费 0、实付 0）。
     */
    @Test
    @DisplayName("全零金额明细可构造")
    void detail_allZero_boundaryAllowed() {
        final AmountDetail detail = new AmountDetail(0L, 0L, 0L, 0L);

        assertThat(detail.getPaidAmount()).isZero();
    }

    /**
     * boundary：discount 预留位可构造——恒等式成立即通过（优惠超过
     * 商品+运费时等式成立但实付为负，由非负断言拦截；本用例恰好非负，
     * 验证优惠叠加在 VO 层不需改结构）。
     */
    @Test
    @DisplayName("discount 预留位可构造")
    void detail_positiveDiscount_identityHolds() {
        final AmountDetail detail = new AmountDetail(1000L, 120L, 20L, 1100L);

        assertThat(detail.getDiscount()).isEqualTo(20L);
        assertThat(detail.getPaidAmount()).isEqualTo(1100L);
    }

    /**
     * error：商品总额为负拒绝。
     */
    @Test
    @DisplayName("负商品总额拒绝")
    void detail_negativeGoodsAmount_rejected() {
        assertThatThrownBy(() -> new AmountDetail(-1L, 0L, 0L, 0L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code()));
    }

    /**
     * error：运费为负拒绝。
     */
    @Test
    @DisplayName("负运费拒绝")
    void detail_negativeFreightAmount_rejected() {
        assertThatThrownBy(() -> new AmountDetail(1000L, -1L, 0L, 999L))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：优惠为负拒绝。
     */
    @Test
    @DisplayName("负优惠金额拒绝")
    void detail_negativeDiscount_rejected() {
        assertThatThrownBy(() -> new AmountDetail(1000L, 120L, -1L, 1121L))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：实付与恒等式不符拒绝（实付多计 1 分——装配错乱拦截）。
     */
    @Test
    @DisplayName("实付与恒等式不符拒绝")
    void detail_mismatchedPaidAmount_rejected() {
        assertThatThrownBy(() -> new AmountDetail(1000L, 120L, 0L, 1121L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.ORDER_SNAPSHOT_AMOUNT_INVALID.code()));
    }

    /**
     * error：恒等式成立但实付为负拒绝（优惠 50 分超商品+运费——
     * 负订单拦截）。
     */
    @Test
    @DisplayName("恒等式成立但实付为负拒绝")
    void detail_identityHoldsButNegativePaid_rejected() {
        assertThatThrownBy(() -> new AmountDetail(0L, 0L, 50L, -50L))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * freeze：快照无任何变更路径——字段全 final、无一字面 set 方法
     * （冻结语义的反射层确认，与 OrderItem/AddressSnapshot 快照同标准）。
     */
    @Test
    @DisplayName("金额明细冻结：字段 final 且无 setter")
    void detail_noMutators() throws Exception {
        for (final Field field : AmountDetail.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 {} 必须为 final（快照冻结）", field.getName()).isTrue();
        }
        for (final Method method : AmountDetail.class.getDeclaredMethods()) {
            assertThat(method.getName()).as("金额明细不得暴露 setter {}：{}", method.getName(), method)
                    .doesNotStartWith("set");
        }
    }
}