package com.nona.domain.catalog.service;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.ports.FreightCalculator;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费计算器单元测试：三规则计算语义与领域守卫（跨域契约冻结面）。
 * <p>
 * 覆盖：happy（包邮恒 0 / 按件单价×件数 / 满额免邮达额即 0）、
 * critical（0 件、恰好达额、未达额、金额 0）、
 * error（停用模板拒绝——新订单领域守卫、负金额/负件数/空模板防御）。
 * 金额单位均为分。
 */
class FreightCalculatorImplUnitTest {

    /**
     * 被测计算器
     */
    private final FreightCalculator calculator = new FreightCalculatorImpl();

    /**
     * happy：包邮规则恒为 0——任意金额与件数。
     */
    @Test
    @DisplayName("包邮规则恒为0")
    void free_alwaysZero() {
        final FreightTemplate template = template(FreightRuleType.FREE, null, null, null);
        assertThat(calculator.calculate(template, 0L, 0)).isZero();
        assertThat(calculator.calculate(template, 9999L, 10)).isZero();
        assertThat(calculator.calculate(template, 100_000_000L, 1000)).isZero();
    }

    /**
     * happy：按件规则运费 = 单价 × 件数。
     */
    @Test
    @DisplayName("按件规则按单价乘件数")
    void perItem_multipliesCount() {
        final FreightTemplate template = template(FreightRuleType.PER_ITEM, 800L, null, null);
        assertThat(calculator.calculate(template, 0L, 3)).isEqualTo(2400L);
        assertThat(calculator.calculate(template, 12000L, 10)).isEqualTo(8000L);
    }

    /**
     * critical：按件规则 0 件运费为 0（件数不影响金额维度）。
     */
    @Test
    @DisplayName("按件规则0件运费为0")
    void perItem_zeroCountZero() {
        final FreightTemplate template = template(FreightRuleType.PER_ITEM, 800L, null, null);
        assertThat(calculator.calculate(template, 9900L, 0)).isZero();
    }

    /**
     * happy：满额免邮未达阈值收基础运费。
     */
    @Test
    @DisplayName("满额免邮未达阈收取基础运费")
    void thresholdFree_belowThresholdChargesBase() {
        final FreightTemplate template = template(FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L);
        assertThat(calculator.calculate(template, 9899L, 2)).isEqualTo(1000L);
    }

    /**
     * critical：满额免邮恰好达阈值为 0（达标即包邮，含边界）。
     */
    @Test
    @DisplayName("满额免邮恰好达额即包邮")
    void thresholdFree_exactlyAtThresholdFree() {
        final FreightTemplate template = template(FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L);
        assertThat(calculator.calculate(template, 9900L, 3)).isZero();
    }

    /**
     * critical：满额免邮超过阈值（阈值+1 分）为 0。
     */
    @Test
    @DisplayName("满额免邮超过阈值即包邮")
    void thresholdFree_aboveThresholdFree() {
        final FreightTemplate template = template(FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L);
        assertThat(calculator.calculate(template, 9901L, 5)).isZero();
        assertThat(calculator.calculate(template, 100_000L, 50)).isZero();
    }

    /**
     * critical：满额免邮金额为 0（低于阈值）收基础运费。
     */
    @Test
    @DisplayName("满额免邮金额0收基础运费")
    void thresholdFree_zeroAmountChargesBase() {
        final FreightTemplate template = template(FreightRuleType.THRESHOLD_FREE, null, 1000L, 1L);
        assertThat(calculator.calculate(template, 0L, 0)).isEqualTo(1000L);
    }

    /**
     * error：停用模板拒绝计费——新订单领域守卫（disabled 模板新建订单不可用）。
     */
    @Test
    @DisplayName("停用模板拒绝计费")
    void disabledTemplate_rejected() {
        final FreightTemplate template = new FreightTemplate(1L, 1001L, "按件",
                FreightRuleType.PER_ITEM, 800L, null, null, FreightTemplateStatus.DISABLED);

        assertThatThrownBy(() -> calculator.calculate(template, 9900L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
    }

    /**
     * error：空模板拒绝计费（防御）。
     */
    @Test
    @DisplayName("空模板拒绝计费")
    void nullTemplate_rejected() {
        assertThatThrownBy(() -> calculator.calculate(null, 9900L, 2))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：负金额拒绝计费（防御）。
     */
    @Test
    @DisplayName("负商品金额拒绝计费")
    void negativeAmount_rejected() {
        final FreightTemplate template = template(FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L);
        assertThatThrownBy(() -> calculator.calculate(template, -1L, 2))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：负件数拒绝计费（防御）。
     */
    @Test
    @DisplayName("负件数拒绝计费")
    void negativeCount_rejected() {
        final FreightTemplate template = template(FreightRuleType.PER_ITEM, 800L, null, null);
        assertThatThrownBy(() -> calculator.calculate(template, 9900L, -1))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * critical：按件规则大件数乘积正确（long 域内无溢出）。
     */
    @Test
    @DisplayName("按件规则大件数乘积正确")
    void perItem_largeCountMultiplies() {
        final FreightTemplate template = template(FreightRuleType.PER_ITEM, 50L, null, null);
        assertThat(calculator.calculate(template, 0L, 100_000)).isEqualTo(5_000_000L);
    }

    /**
     * 构造启用状态模板（测试数据）。
     *
     * @param ruleType      规则类型
     * @param perItemPrice  按件单价（分）
     * @param baseFreight   基础运费（分）
     * @param freeThreshold 免邮阈值（分）
     * @return 启用模板
     */
    private static FreightTemplate template(FreightRuleType ruleType, Long perItemPrice,
                                            Long baseFreight, Long freeThreshold) {
        return new FreightTemplate(1L, 1001L, "测试模板", ruleType,
                perItemPrice, baseFreight, freeThreshold, FreightTemplateStatus.ENABLED);
    }
}