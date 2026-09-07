package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费模板聚合根单元测试：规则归一化与校验、启用/停用状态。
 * <p>
 * 覆盖：happy（三规则创建/改规则/启停切换）、critical（重复启停幂等、
 * 多余参数归一化清理）、error（空名称、参数缺失/非正、规则类型缺失拒绝）。
 * 金额单位均为分。
 */
class FreightTemplateUnitTest {

    /**
     * happy：包邮模板创建——状态 ENABLED、无计费参数。
     */
    @Test
    @DisplayName("包邮模板创建默认启用且无计费参数")
    void createFree_hasNoBillingParams() {
        final FreightTemplate template = new FreightTemplate(1L, 1001L, "全场包邮",
                FreightRuleType.FREE, null, null, null, FreightTemplateStatus.ENABLED);

        assertThat(template.getId()).isEqualTo(1L);
        assertThat(template.getShopId()).isEqualTo(1001L);
        assertThat(template.getName()).isEqualTo("全场包邮");
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.FREE);
        assertThat(template.getPerItemPrice()).isNull();
        assertThat(template.getBaseFreight()).isNull();
        assertThat(template.getFreeThreshold()).isNull();
        assertThat(template.isEnabled()).isTrue();
    }

    /**
     * happy：按件模板创建——单价保留。
     */
    @Test
    @DisplayName("按件模板创建保留单价")
    void createPerItem_keepsUnitPrice() {
        final FreightTemplate template = perItem(800L);
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.PER_ITEM);
        assertThat(template.getPerItemPrice()).isEqualTo(800L);
        assertThat(template.getBaseFreight()).isNull();
        assertThat(template.getFreeThreshold()).isNull();
    }

    /**
     * happy：满额免邮模板创建——基础运费与阈值保留。
     */
    @Test
    @DisplayName("满额免邮模板创建保留基础运费与阈值")
    void createThresholdFree_keepsParams() {
        final FreightTemplate template = thresholdFree(1000L, 9900L);
        assertThat(template.getBaseFreight()).isEqualTo(1000L);
        assertThat(template.getFreeThreshold()).isEqualTo(9900L);
        assertThat(template.getPerItemPrice()).isNull();
    }

    /**
     * critical：创建时传入与规则不匹配的多余参数被归一化清理
     * （包邮规则下按件单价/基础运费/阈值全部归空，不变量：参数与规则恒一致）。
     */
    @Test
    @DisplayName("多余参数按规则归一化清理")
    void createFree_ignoresStrayParams() {
        final FreightTemplate template = new FreightTemplate(1L, 1001L, "全场包邮",
                FreightRuleType.FREE, 800L, 1000L, 9900L, FreightTemplateStatus.ENABLED);

        assertThat(template.getPerItemPrice()).isNull();
        assertThat(template.getBaseFreight()).isNull();
        assertThat(template.getFreeThreshold()).isNull();
    }

    /**
     * happy：更新规则——名称与规则整体替换，多余参数归一化清理。
     */
    @Test
    @DisplayName("更新规则整体生效且状态不变")
    void updateRules_replacesRuleAndKeepsStatus() {
        final FreightTemplate template = perItem(800L);
        template.disable();

        template.updateRules("满99包邮", FreightRuleType.THRESHOLD_FREE, 800L, 1000L, 9900L);

        assertThat(template.getName()).isEqualTo("满99包邮");
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.THRESHOLD_FREE);
        assertThat(template.getPerItemPrice()).isNull();
        assertThat(template.getBaseFreight()).isEqualTo(1000L);
        assertThat(template.getFreeThreshold()).isEqualTo(9900L);
        assertThat(template.isEnabled()).isFalse();
    }

    /**
     * happy：停用后状态切换为 DISABLED。
     */
    @Test
    @DisplayName("停用后模板状态为停用")
    void disable_flipsStatus() {
        final FreightTemplate template = perItem(800L);
        template.disable();
        assertThat(template.isEnabled()).isFalse();
        assertThat(template.getStatus()).isEqualTo(FreightTemplateStatus.DISABLED);
    }

    /**
     * happy：停用后重新启用恢复可用。
     */
    @Test
    @DisplayName("重新启用后模板恢复可用")
    void enable_afterDisableRestores() {
        final FreightTemplate template = perItem(800L);
        template.disable();
        template.enable();
        assertThat(template.isEnabled()).isTrue();
    }

    /**
     * critical：重复停用幂等——不报错、状态保持停用。
     */
    @Test
    @DisplayName("重复停用幂等")
    void disable_twiceIdempotent() {
        final FreightTemplate template = perItem(800L);
        template.disable();
        template.disable();
        assertThat(template.isEnabled()).isFalse();
    }

    /**
     * critical：重复启用幂等——不报错、状态保持启用。
     */
    @Test
    @DisplayName("重复启用幂等")
    void enable_twiceIdempotent() {
        final FreightTemplate template = perItem(800L);
        template.enable();
        assertThat(template.isEnabled()).isTrue();
    }

    /**
     * critical：满额免邮阈值恰好 1 分合法（最小正阈值）。
     */
    @Test
    @DisplayName("阈值为最小正整数合法")
    void thresholdFree_minThresholdAccepted() {
        final FreightTemplate template = thresholdFree(1L, 1L);
        assertThat(template.getFreeThreshold()).isEqualTo(1L);
    }

    /**
     * error：空名称创建拒绝。
     */
    @Test
    @DisplayName("空模板名称拒绝创建")
    void blankName_rejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "  ",
                FreightRuleType.FREE, null, null, null, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称");
    }

    /**
     * error：更新时新名称为空拒绝。
     */
    @Test
    @DisplayName("空模板名称拒绝更新")
    void updateRules_blankNameRejected() {
        final FreightTemplate template = perItem(800L);
        assertThatThrownBy(() -> template.updateRules(" ", FreightRuleType.FREE, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称");
    }

    /**
     * error：按件规则缺失单价拒绝。
     */
    @Test
    @DisplayName("按件规则缺失单价拒绝")
    void perItem_missingPriceRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "按件",
                FreightRuleType.PER_ITEM, null, null, null, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("按件");
    }

    /**
     * error：按件规则单价为零拒绝。
     */
    @Test
    @DisplayName("按件规则单价非正拒绝")
    void perItem_nonPositivePriceRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "按件",
                FreightRuleType.PER_ITEM, 0L, null, null, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：满额免邮规则缺失基础运费拒绝。
     */
    @Test
    @DisplayName("满额免邮缺失基础运费拒绝")
    void thresholdFree_missingBaseFreightRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "满额",
                FreightRuleType.THRESHOLD_FREE, null, null, 9900L, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("基础运费");
    }

    /**
     * error：满额免邮基础运费为零拒绝（零基础运费与包邮语义重叠）。
     */
    @Test
    @DisplayName("满额免邮基础运费非正拒绝")
    void thresholdFree_nonPositiveBaseRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "满额",
                FreightRuleType.THRESHOLD_FREE, null, 0L, 9900L, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：满额免邮缺失阈值拒绝。
     */
    @Test
    @DisplayName("满额免邮缺失阈值拒绝")
    void thresholdFree_missingThresholdRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "满额",
                FreightRuleType.THRESHOLD_FREE, null, 1000L, null, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阈值");
    }

    /**
     * error：满额免邮阈值非正拒绝。
     */
    @Test
    @DisplayName("满额免邮阈值非正拒绝")
    void thresholdFree_nonPositiveThresholdRejected() {
        assertThatThrownBy(() -> new FreightTemplate(1L, 1001L, "满额",
                FreightRuleType.THRESHOLD_FREE, null, 1000L, -1L, FreightTemplateStatus.ENABLED))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：更新时规则类型为 null 拒绝。
     */
    @Test
    @DisplayName("规则类型缺失拒绝更新")
    void updateRules_nullRuleTypeRejected() {
        final FreightTemplate template = perItem(800L);
        assertThatThrownBy(() -> template.updateRules("新名称", null, 800L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("规则类型");
    }

    /**
     * 构造按件模板（测试数据）。
     *
     * @param perItemPrice 按件单价（分）
     * @return 按件模板
     */
    private static FreightTemplate perItem(Long perItemPrice) {
        return new FreightTemplate(1L, 1001L, "按件模板",
                FreightRuleType.PER_ITEM, perItemPrice, null, null, FreightTemplateStatus.ENABLED);
    }

    /**
     * 构造满额免邮模板（测试数据）。
     *
     * @param baseFreight   基础运费（分）
     * @param freeThreshold 免邮阈值（分）
     * @return 满额免邮模板
     */
    private static FreightTemplate thresholdFree(Long baseFreight, Long freeThreshold) {
        return new FreightTemplate(1L, 1001L, "满额免邮模板",
                FreightRuleType.THRESHOLD_FREE, null, baseFreight, freeThreshold, FreightTemplateStatus.ENABLED);
    }
}