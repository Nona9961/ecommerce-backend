package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费模板工厂单元测试：创建入口的归属校验与初始状态定型。
 * <p>
 * 覆盖：happy（三规则创建成功、Snowflake ID 生成、初始启用）、
 * error（归属店铺缺失拒绝；名称与规则参数校验由聚合构造器兜底）。
 */
class FreightTemplateFactoryTest {

    /**
     * 被测工厂
     */
    private final FreightTemplateFactory factory = new FreightTemplateFactory();

    /**
     * happy：包邮模板创建——生成 ID、归属店铺、初始启用。
     */
    @Test
    @DisplayName("创建包邮模板生成ID且默认启用")
    void createFree_generatesIdAndEnabled() {
        final FreightTemplate template = factory.createFreightTemplate(
                1001L, "全场包邮", FreightRuleType.FREE, null, null, null);

        assertThat(template.getId()).isNotNull();
        assertThat(template.getShopId()).isEqualTo(1001L);
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.FREE);
        assertThat(template.isEnabled()).isTrue();
        assertThat(template.getPerItemPrice()).isNull();
    }

    /**
     * happy：按件模板创建——单价保留。
     */
    @Test
    @DisplayName("创建按件模板保留单价")
    void createPerItem_keepsUnitPrice() {
        final FreightTemplate template = factory.createFreightTemplate(
                1001L, "按件模板", FreightRuleType.PER_ITEM, 800L, null, null);

        assertThat(template.getPerItemPrice()).isEqualTo(800L);
        assertThat(template.getBaseFreight()).isNull();
        assertThat(template.getFreeThreshold()).isNull();
    }

    /**
     * happy：满额免邮模板创建——基础运费与阈值保留。
     */
    @Test
    @DisplayName("创建满额免邮模板保留基础运费与阈值")
    void createThresholdFree_keepsParams() {
        final FreightTemplate template = factory.createFreightTemplate(
                1001L, "满99包邮", FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L);

        assertThat(template.getBaseFreight()).isEqualTo(1000L);
        assertThat(template.getFreeThreshold()).isEqualTo(9900L);
    }

    /**
     * happy：多次创建 ID 互不相同。
     */
    @Test
    @DisplayName("多次创建ID互不相同")
    void createTwice_idsDiffer() {
        final FreightTemplate first = factory.createFreightTemplate(
                1001L, "模板一", FreightRuleType.FREE, null, null, null);
        final FreightTemplate second = factory.createFreightTemplate(
                1001L, "模板二", FreightRuleType.FREE, null, null, null);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    /**
     * error：归属店铺缺失拒绝创建。
     */
    @Test
    @DisplayName("归属店铺缺失拒绝创建")
    void createNullShopId_rejected() {
        assertThatThrownBy(() -> factory.createFreightTemplate(
                null, "包邮", FreightRuleType.FREE, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：模板名称为空拒绝（聚合构造器校验兜底）。
     */
    @Test
    @DisplayName("空模板名称拒绝创建")
    void createBlankName_rejected() {
        assertThatThrownBy(() -> factory.createFreightTemplate(
                1001L, " ", FreightRuleType.FREE, null, null, null))
                .isInstanceOf(BusinessException.class);
    }
}