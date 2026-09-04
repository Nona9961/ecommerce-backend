package com.nona.domain.catalog.ports;

import java.util.List;

/**
 * 买家商品视图（ProductQueryFacade 返回载体，域间只读契约的冻结字段
 * 清单——B6.1 详情元素 + B6.2 SKU 联动数据 + 下单前置读字段一次成型）：
 * <ul>
 *     <li>主体：名称/描述/图片/自定义属性（B6.1 ①信息完整展示）；</li>
 *     <li>规格联动：规格维度结构（维度名 + 可选值——前端规格选择区）+ SKU
 *         级精确价与可售量（B6.2 ①规格项联动②无库存置灰③价格/库存随动
 *         ——按 skuId zip 可售，缺行 0 置灰）；</li>
 *     <li>运费说明：绑定运费模板规则概要（未绑定=null——详情运费区按无
 *         模板呈现；运费试算不在本读面，结算页试算由订单域经运费计算器
 *         完成）；</li>
 *     <li>店铺卡片：店名/logo（B6.1 店铺展示；店铺为 global 聚合，买家
 *         读无需放行）。</li>
 * </ul>
 * 金额单位分；价格 null=未定价（在售商品不变量保证定价齐备，防御形态
 * 由实现在售校验承载）。视图为生效内容（待审草稿买家不可见）。
 *
 * @param productId         商品 ID
 * @param shopId            归属店铺 ID（下单拆单按店铺分组的锚点）
 * @param name              商品名称
 * @param description       商品描述（可空）
 * @param images            图片引用（URL + 主图标记，按加入序）
 * @param attributes        自定义属性键值对（按加入序）
 * @param specDimensions    规格维度结构（规格选择区渲染）
 * @param skus              SKU 行（价格 + 可售量，按模板展开序）
 * @param freight           绑定运费模板规则概要（未绑定=null）
 * @param shop              店铺卡片（买家详情展示）
 * @author nona9961
 */
public record ProductBuyerView(
        Long productId,
        Long shopId,
        String name,
        String description,
        List<Image> images,
        List<Attribute> attributes,
        List<SpecDimension> specDimensions,
        List<Sku> skus,
        Freight freight,
        Shop shop
) {

    /**
     * 图片引用（URL + 主图标记）。
     *
     * @param url     URL（/files/{objectKey} 形态）
     * @param primary 是否主图（至多一条为 true）
     */
    public record Image(String url, boolean primary) {
    }

    /**
     * 自定义属性键值对。
     *
     * @param key   属性键
     * @param value 属性值（可空）
     */
    public record Attribute(String key, String value) {
    }

    /**
     * 规格维度（规格选择区的一维：维度名 + 可选值列表，按配置序）。
     *
     * @param name   维度名（如 颜色）
     * @param values 可选值列表（如 黑/白）
     */
    public record SpecDimension(String name, List<String> values) {
    }

    /**
     * SKU 行（规格联动数据：specHash 与展开序对齐——联动选择后按摘要
     * 定位 SKU；可售量 zip 呈现，缺行/跨店铺按 0=置灰不可选）。
     *
     * @param skuId       SKU ID
     * @param specHash    规格组合规范化摘要
     * @param specSummary 规格组合可读摘要
     * @param price       销售价（分）
     * @param available   可售量（买家可见库存——经库存域读）
     */
    public record Sku(Long skuId, String specHash, String specSummary,
                      Long price, int available) {
    }

    /**
     * 运费模板规则概要（详情运费区展示；未绑定=null——前端按无运费
     * 模板呈现）。
     *
     * @param templateId     模板 ID
     * @param name           模板名称
     * @param ruleType       规则类型（FREE/PER_ITEM/THRESHOLD_FREE）
     * @param perItemPrice   按件单价（分；仅 PER_ITEM 非 null）
     * @param baseFreight    基础运费（分；仅 THRESHOLD_FREE 非 null）
     * @param freeThreshold  免邮阈值（分；仅 THRESHOLD_FREE 非 null）
     */
    public record Freight(Long templateId, String name, String ruleType,
                          Long perItemPrice, Long baseFreight, Long freeThreshold) {
    }

    /**
     * 店铺卡片（B6.1 店铺展示）。
     *
     * @param shopId 店铺 ID
     * @param name   店铺名称
     * @param logo   logo URL（可空）
     */
    public record Shop(Long shopId, String name, String logo) {
    }
}
