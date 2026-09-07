package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 默认运费模板工厂契约测试：开店编排唯一创建入口的初始形态冻结——
 * 名称（默认模板名称常量）、规则 FREE（无计费参数）、状态 ENABLED、
 * 默认身份 true、归属店铺定型；归属缺失拒绝（catalog.shop_required 400，
 * 与既有 createFreightTemplate 同形态）。
 * <p>
 * 红阶段：createDefaultFreightTemplate 为签名冻结（实现缺失）——
 * 两用例红于 UnsupportedOperationException，绿阶段实现后转为断言期
 * 验证。
 */
class FreightTemplateFactoryDefaultTest {

    /**
     * 归属店铺 ID
     */
    private static final long SHOP_ID = 1001L;

    private final FreightTemplateFactory factory = new FreightTemplateFactory();

    /**
     * happy：默认模板初始形态完整（名称/包邮/启用/默认身份/归属定型）。
     */
    @Test
    @DisplayName("默认模板固定初始形态")
    void createDefault_hasFrozenInitialShape() {
        final FreightTemplate template = factory.createDefaultFreightTemplate(SHOP_ID);

        assertThat(template.getShopId()).isEqualTo(SHOP_ID);
        assertThat(template.getName()).isEqualTo(FreightTemplateFactory.DEFAULT_TEMPLATE_NAME);
        assertThat(template.getRuleType()).isEqualTo(FreightRuleType.FREE);
        assertThat(template.getPerItemPrice()).isNull();
        assertThat(template.getBaseFreight()).isNull();
        assertThat(template.getFreeThreshold()).isNull();
        assertThat(template.isEnabled()).isTrue();
        assertThat(template.isDefault()).isTrue();
    }

    /**
     * fail：归属店铺缺失拒绝（default 模板归店铺租户，must 非空——与
     * 既有 createFreightTemplate 同形态 shop_required 400）。
     */
    @Test
    @DisplayName("默认模板归属店铺缺失拒绝")
    void createDefault_nullShop_rejected() {
        assertThatThrownBy(() -> factory.createDefaultFreightTemplate(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("catalog.shop_required");
    }
}