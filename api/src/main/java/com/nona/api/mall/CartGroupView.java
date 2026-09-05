package com.nona.api.mall;

import java.util.List;

/**
 * 购物车店铺分组视图（列表响应组成单元）：跨店铺条目按店铺分组展示。
 * <p>
 * 分组锚点为店铺 ID（条目创建时从买家商品视图冗余）；店铺名为创建时
 * 快照（展示软状态，商家改名后购物车展示旧名直至下次加购——订单落单
 * 时的店铺信息冻结属订单快照语义）。合计为组内条目数量之和（金额合计
 * 依赖价格读面，属结算试算演进项）。
 *
 * @param shopId       店铺 ID（分组锚点）
 * @param shopName     店铺名称（创建时快照）
 * @param totalQuantity 组内条目数量合计
 * @param items        组内条目（按加购序）
 *
 * @author nona9961
 */
public record CartGroupView(
        Long shopId,
        String shopName,
        Integer totalQuantity,
        List<CartEntryView> items
) {
}