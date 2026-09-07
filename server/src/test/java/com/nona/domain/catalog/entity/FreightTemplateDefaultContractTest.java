package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 默认运费模板身份契约测试（非空设计 §2.5 领域侧落点）：默认模板 =
 * 既有 {@code FreightTemplate} 聚合的一个具名实例（isDefault=true 显式
 * 标记店铺回退锚点），身份创建后不可变；规则可编辑（冻结的是存在性
 * 而非规则形态）；默认模板禁停用（frozen 400）；普通模板（8 参构造）
 * 恒非默认。
 * <p>
 * 红阶段：9 参构造器/isDefault()/disable() 为签名冻结（实现缺失）——
 * 四用例红于 UnsupportedOperationException（或断言期），绿阶段实现后
 * 转为断言期验证。
 */
class FreightTemplateDefaultContractTest {

    /**
     * 归属店铺 ID
     */
    private static final long SHOP_ID = 1001L;

    /**
     * 默认模板初始形态（开店建 default 模板：包邮 FREE + 启用 + 默认身份）。
     */
    private static FreightTemplate defaultTemplate() {
        return new FreightTemplate(1L, SHOP_ID, "默认运费模板",
                FreightRuleType.FREE, null, null, null,
                FreightTemplateStatus.ENABLED, true);
    }

    /**
     * happy：默认身份构造暴露默认标记（isDefault=true——开店编排创建
     * 的默认模板身份可识别，回退锚点定位依据）。
     */
    @Test
    @DisplayName("默认身份构造暴露默认标记")
    void defaultTemplate_ctor_exposesDefaultFlag() {
        final FreightTemplate template = defaultTemplate();

        assertThat(template.isDefault()).isTrue();
    }

    /**
     * critical：默认模板规则可编辑且默认身份保持（updateRules 无守卫——
     * 店铺可将默认运费从包邮改为按件/满额免邮，冻结的是存在性而非规则）。
     */
    @Test
    @DisplayName("默认模板规则可编辑且身份保持")
    void defaultTemplate_rulesEditable_keepsDefaultFlag() {
        final FreightTemplate template = defaultTemplate();

        template.updateRules("满99包邮", FreightRuleType.THRESHOLD_FREE, 800L, 1000L, 9900L);

        assertThat(template.getName()).isEqualTo("满99包邮");
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.THRESHOLD_FREE);
        assertThat(template.getBaseFreight()).isEqualTo(1000L);
        assertThat(template.getFreeThreshold()).isEqualTo(9900L);
        assertThat(template.isDefault()).isTrue();
    }

    /**
     * fail：默认模板禁停用——disable() 拒绝（catalog.freight_default_template_frozen
     * 400，回退锚点恒可用守卫）。
     */
    @Test
    @DisplayName("默认模板停用被拒绝")
    void defaultTemplate_disable_rejected() {
        final FreightTemplate template = defaultTemplate();

        assertThatThrownBy(template::disable)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("catalog.freight_default_template_frozen");
    }

    /**
     * happy（回归锚点）：8 参构造普通模板恒非默认（既有创建路径不变；
     * 默认身份只经默认构造路径进入）。
     */
    @Test
    @DisplayName("普通模板恒非默认")
    void normalTemplate_8argCtor_notDefault() {
        final FreightTemplate template = new FreightTemplate(1L, SHOP_ID, "普通包邮模板",
                FreightRuleType.FREE, null, null, null, FreightTemplateStatus.ENABLED);

        assertThat(template.isDefault()).isFalse();
    }
}