package com.nona.api.seller;

import java.util.List;

/**
 * 商品店铺分类绑定请求（商品侧多对多——编辑页多选勾选的保存形态）：
 * 整体替换语义——提交的目标分类集合即商品的完整绑定集（与既有绑定的差
 * 集由服务端计算），空集合=清空全部绑定。绑定目标必须属于商品所属店铺
 * （跨店铺分类按不存在呈现 404，不泄露归属）。
 *
 * @param shopCategoryIds 目标店铺分类 ID 集合（可空=清空全部绑定）
 * @author nona9961
 */
public record ShopCategoryBindRequest(List<Long> shopCategoryIds) {
}
