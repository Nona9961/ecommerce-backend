package com.nona.application.mall;

import com.nona.api.mall.BuyerAttributeView;
import com.nona.api.mall.BuyerFreightView;
import com.nona.api.mall.BuyerProductDetail;
import com.nona.api.mall.BuyerShopView;
import com.nona.api.mall.BuyerSkuView;
import com.nona.api.mall.BuyerSpecDimensionView;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.inf.context.CrossTenant;
import org.springframework.stereotype.Service;

/**
 * 买家端商品查询用例：商品详情读（主库强一致）编排——经
 * {@link CrossTenant} 显式放行后调用商品查询门面（买家视角 contextTenant
 * 空，读 tenant 表商品/SKU/运费模板必须读放行——放行只出现在应用层用例
 * 方法），并在本层完成买家视图 → 契约 DTO 的映射。
 * <p>
 * 语义：非在售商品统一按不存在呈现（404，不泄露生命周期状态——由门面
 * 在售校验承载）；视图承载生效内容（待审草稿买家不可见）；运费/店铺
 * 卡片按视图原样传递（未绑定/行缺失为 null，前端按无模板/降级呈现）。
 * 事务边界：查询类用例不开写事务；放行作用域 = 用例方法执行范围。
 *
 * @author nona9961
 */
@Service
public class ProductQueryUseCase {

    /**
     * 商品查询门面（买家视图装配：在售校验 + 生效内容 + 运费概要 +
     * 店铺卡片 + SKU 可售 zip）
     */
    private final ProductQueryFacade productQueryFacade;

    /**
     * 构造买家商品查询用例。
     *
     * @param productQueryFacade 商品查询门面
     */
    public ProductQueryUseCase(ProductQueryFacade productQueryFacade) {
        this.productQueryFacade = productQueryFacade;
    }

    /**
     * 商品详情（详情页数据）。
     *
     * @param productId 商品 ID
     * @return 买家商品详情
     */
    @CrossTenant
    public BuyerProductDetail getProduct(Long productId) {
        final ProductBuyerView view = productQueryFacade.getBuyerView(productId);
        return new BuyerProductDetail(
                view.productId(),
                view.shopId(),
                view.name(),
                view.description(),
                toMainImageUrl(view),
                view.images().stream().map(ProductBuyerView.Image::url).toList(),
                view.attributes().stream()
                        .map(attribute -> new BuyerAttributeView(attribute.key(), attribute.value()))
                        .toList(),
                view.specDimensions().stream()
                        .map(dimension -> new BuyerSpecDimensionView(dimension.name(), dimension.values()))
                        .toList(),
                view.skus().stream()
                        .map(sku -> new BuyerSkuView(sku.skuId(), sku.specHash(), sku.specSummary(),
                                sku.price(), sku.available()))
                        .toList(),
                toFreightView(view),
                toShopView(view));
    }

    /**
     * 视图商品 → 契约 DTO：主图 URL（在售商品必有主图——首个主图标记
     * 命中；无主图为脏数据形态按 null 呈现）。
     *
     * @param view 买家商品视图
     * @return 主图 URL；无主图返回 null
     */
    private static String toMainImageUrl(ProductBuyerView view) {
        return view.images().stream()
                .filter(ProductBuyerView.Image::primary)
                .map(ProductBuyerView.Image::url)
                .findFirst()
                .orElse(null);
    }

    /**
     * 视图运费概要 → 契约 DTO（null 原样传递：未绑定模板按无运费模板
     * 呈现）。
     *
     * @param view 买家商品视图
     * @return 运费概要；未绑定返回 null
     */
    private static BuyerFreightView toFreightView(ProductBuyerView view) {
        final ProductBuyerView.Freight freight = view.freight();
        if (freight == null) {
            return null;
        }
        return new BuyerFreightView(freight.templateId(), freight.name(), freight.ruleType(),
                freight.perItemPrice(), freight.baseFreight(), freight.freeThreshold());
    }

    /**
     * 视图店铺卡片 → 契约 DTO（null 原样传递：店铺行缺失脏数据降级）。
     *
     * @param view 买家商品视图
     * @return 店铺卡片；店铺行缺失返回 null
     */
    private static BuyerShopView toShopView(ProductBuyerView view) {
        final ProductBuyerView.Shop shop = view.shop();
        if (shop == null) {
            return null;
        }
        return new BuyerShopView(shop.shopId(), shop.name(), shop.logo());
    }
}