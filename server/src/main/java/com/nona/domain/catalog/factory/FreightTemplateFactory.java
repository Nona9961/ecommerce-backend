package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 运费模板工厂：模板聚合根的创建入口（ID 生成 + 归属定型 + 初始状态）。
 * <p>
 * 创建校验：归属店铺必填非空（模板归属商家数据，落库随租户写入）；
 * 模板名称与计费参数校验由聚合构造器收敛（不变量兜底）。初始状态恒为
 * ENABLED（商家创建即启用，停用由商家显式操作）。
 *
 * @author nona9961
 */
@Component
public class FreightTemplateFactory {

    /**
     * 店铺默认运费模板名称（开店编排自动创建时的初始命名；可编辑——
     * 名称可改，回退定位由默认身份标记承载，不依赖名称）。
     */
    public static final String DEFAULT_TEMPLATE_NAME = "默认运费模板";

    /**
     * 创建店铺默认运费模板（开店编排唯一创建入口）：
     * 初始规则=包邮 FREE（无计费参数）、初始状态 ENABLED、默认身份标记
     * true、归属店铺必填（{@code catalog.shop_required} 400，与既有
     * {@link #createFreightTemplate} 同形态）。默认模板与普通模板同构
     * （同表同实体，可编辑），身份创建后不可变。
     *
     * @param shopId 归属店铺 ID（必填非空；tenant=shopId）
     * @return 新建默认模板（启用 + 包邮 + 默认身份）
     */
    public FreightTemplate createDefaultFreightTemplate(Long shopId) {
        if (shopId == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_REQUIRED.code(),
                    "店铺不能为空");
        }
        return new FreightTemplate(IDUtils.generateID(), shopId, DEFAULT_TEMPLATE_NAME,
                FreightRuleType.FREE, null, null, null, FreightTemplateStatus.ENABLED, true);
    }

    /**
     * 创建运费模板：生成 Snowflake ID、初始状态 ENABLED。
     *
     * @param shopId        归属店铺 ID（必填非空）
     * @param name          模板名称（必填非空）
     * @param ruleType      运费规则类型
     * @param perItemPrice  按件单价（分；PER_ITEM 必填正数）
     * @param baseFreight   基础运费（分；THRESHOLD_FREE 必填正数）
     * @param freeThreshold 免邮阈值（分；THRESHOLD_FREE 必填正数）
     * @return 新建模板（启用状态）
     */
    public FreightTemplate createFreightTemplate(Long shopId, String name, FreightRuleType ruleType,
                                                 Long perItemPrice, Long baseFreight, Long freeThreshold) {
        if (shopId == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_REQUIRED.code(),
                    "店铺不能为空");
        }
        return new FreightTemplate(IDUtils.generateID(), shopId, name, ruleType,
                perItemPrice, baseFreight, freeThreshold, FreightTemplateStatus.ENABLED);
    }
}