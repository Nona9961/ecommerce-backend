package com.nona.api.mall;

import com.nona.api.HttpResponse;

/**
 * 买家端商品查询契约（/mall/products…，BUYER 角色）。
 * <p>
 * 商品详情读（主库强一致）：服务端在售校验（仅 ON_SALE 可读，
 * 其余按不存在呈现 404——草稿/待审核/下架商品买家不可见，fail-closed
 * 不泄露生命周期）；读放行（@CrossTenant）只发生在应用层用例，本契约
 * 只声明端点形态。
 *
 * @author nona9961
 */
public interface BuyerProductApi {

    /**
     * 商品详情（详情页数据：主图/图集/名称/描述/属性/规格
     * 维度与 SKU 价格库存/运费说明/店铺卡片）。
     *
     * @param productId 商品 ID（非在售商品按不存在呈现 404）
     * @return 买家商品详情
     */
    HttpResponse<BuyerProductDetail> getProduct(Long productId);
}
