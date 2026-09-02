package com.nona.domain.catalog.service;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.ports.FreightCalculator;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Component;

/**
 * 运费计算器实现（catalog 域领域服务，纯计算无基础设施依赖）。
 * <p>
 * 三规则计算：包邮恒 0；按件 = 单价 × 件数；满额免邮 = 商品金额达阈值
 * 即 0，未达额收基础运费。领域守卫：停用模板拒绝计费（新订单不可用）；
 * 防御校验：空模板、负金额、负件数拒绝（BusinessException）。
 *
 * @author nona9961
 */
@Component
public class FreightCalculatorImpl implements FreightCalculator {

    /**
     * {@inheritDoc}
     */
    @Override
    public long calculate(FreightTemplate template, long itemAmount, int itemCount) {
        if (template == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_INPUT_INVALID.code(),
                    "运费模板不存在");
        }
        if (!template.isEnabled()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_DISABLED.code(),
                    "运费模板已停用，不可用于新订单");
        }
        if (itemAmount < 0) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_INPUT_INVALID.code(),
                    "商品金额不能为负");
        }
        if (itemCount < 0) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_INPUT_INVALID.code(),
                    "商品件数不能为负");
        }
        return switch (template.getRuleType()) {
            case FREE -> 0L;
            case PER_ITEM -> template.getPerItemPrice() * itemCount;
            case THRESHOLD_FREE -> itemAmount >= template.getFreeThreshold() ? 0L : template.getBaseFreight();
        };
    }
}