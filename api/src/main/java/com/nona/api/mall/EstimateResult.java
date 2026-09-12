package com.nona.api.mall;

import java.util.List;

/**
 * 结算试算结果（买家端契约，B7.4/O2 试算）：按店铺分组的商品清单与
 * 金额明细——每组展示店铺名/商品额/运费/小计，整体汇总商品金额、运费
 * 与实付合计（M07 结算页金额明细：商品金额/运费/优惠[二期预留]/实付
 * 合计）。
 * <p>
 * 金额单位均为分；优惠位一期恒 0（营销二期叠加，结构免改）；主单总额
 * = Σ 商品金额 + Σ 运费（B7.4②）。组序与组内条目序按购物车加购序
 * 稳定呈现。
 *
 * @param groups            按店铺分组的有序列表
 * @param totalGoodsAmount  商品金额合计（分，Σ 组商品额）
 * @param totalFreightAmount 运费合计（分，Σ 组运费）
 * @param totalDiscount     优惠合计（分，一期恒 0）
 * @param totalPaidAmount   实付合计（分，商品金额 + 运费 - 优惠）
 * @author nona9961
 */
public record EstimateResult(List<EstimateGroup> groups, long totalGoodsAmount,
                             long totalFreightAmount, long totalDiscount, long totalPaidAmount) {

    /**
     * 单店铺分组（B7.5：每子订单按对应店铺运费模板计运费；组 = 未来
     * 子订单的商品投影）。
     *
     * @param shopId        归属店铺 ID（拆单锚点）
     * @param shopName      店铺名（B7.5③ 展示子订单归属店铺）
     * @param goodsAmount   商品金额（分，Σ 条目小计）
     * @param freightAmount 运费（分，按该店运费模板计算）
     * @param discount      优惠（分，一期恒 0）
     * @param paidAmount    组实付（分，商品金额 + 运费 - 优惠）
     * @param items         组内商品条目（保持购物车加购序）
     */
    public record EstimateGroup(Long shopId, String shopName, long goodsAmount,
                                long freightAmount, long discount, long paidAmount,
                                List<EstimateItem> items) {
    }

    /**
     * 组内商品条目（结算页商品清单行：名称/单价/数量/小计）。
     *
     * @param productId   商品 ID
     * @param skuId       SKU ID
     * @param productName 商品名称
     * @param unitPrice   快照单价（分）
     * @param quantity    数量
     * @param subtotal    小计（分，单价 × 数量）
     */
    public record EstimateItem(Long productId, Long skuId, String productName,
                               long unitPrice, int quantity, long subtotal) {
    }
}