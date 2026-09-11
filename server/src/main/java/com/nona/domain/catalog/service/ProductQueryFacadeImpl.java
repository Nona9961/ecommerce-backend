package com.nona.domain.catalog.service;

import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品查询门面实现（买家视图装配）：商品加载（在售校验：非 ON_SALE
 * 按不存在呈现）→ 生效内容 + 从表集合
 * → 运费模板规则概要（绑定概要；未绑定/绑定悬挂回退<b>店铺默认模板</b>
 * 概要——freight 恒非空，非空设计 §2.5）→ 店铺卡片（Shop 聚合
 * 读）→ SKU 可售量（跨域 InventoryFacade.queryAvailable zip）→ 视图拼装。
 * <p>
 * 租户语义：本实现自身不放行——买家/下单编排视角（contextTenant 空）的
 * 读放行（@CrossTenant）由应用层调用方（mall 详情用例/order 编排）承载；
 * 跨域库存读在同一放行上下文内执行（跨店铺/未初始化 SKU 按可售 0 呈现）。
 * <p>
 * 装配语义（getBuyerView 已接线；回退装配点
 * （{@link #toFreight}）双参签名冻结）。
 *
 * @author nona9961
 */
@Component
public class ProductQueryFacadeImpl implements ProductQueryFacade {

    /**
     * 商品仓储（在售商品加载）
     */
    private final ProductRepository productRepository;

    /**
     * 运费模板仓储（绑定模板规则概要）
     */
    private final FreightTemplateRepository freightTemplateRepository;

    /**
     * 店铺仓储（店铺卡片）
     */
    private final ShopRepository shopRepository;

    /**
     * 库存门面（SKU 可售量跨上下文读）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 构造查询门面实现。
     *
     * @param productRepository          商品仓储
     * @param freightTemplateRepository  运费模板仓储
     * @param shopRepository             店铺仓储
     * @param inventoryFacade            库存门面
     */
    public ProductQueryFacadeImpl(ProductRepository productRepository,
                                  FreightTemplateRepository freightTemplateRepository,
                                  ShopRepository shopRepository,
                                  InventoryFacade inventoryFacade) {
        this.productRepository = productRepository;
        this.freightTemplateRepository = freightTemplateRepository;
        this.shopRepository = shopRepository;
        this.inventoryFacade = inventoryFacade;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 装配语义：商品加载（租户过滤在调用方放行上下文内生效）→ 非
     * ON_SALE 按不存在呈现（{@code CATALOG_PRODUCT_NOT_FOUND} 404，不
     * 泄露生命周期状态）→ 生效内容 + 从表集合映射（SKU 行按模板展开序
     * 全量——停用 SKU 不出售可售置 0，启用 SKU 经库存门面 zip 可售，缺行
     * 按 0 置灰）→ 运费模板规则概要（绑定行存在 → 绑定概要；未绑定/绑定
     * 悬挂 → 回退 {@code findDefaultByShopId} 概要；默认缺失 → 防御拒绝
     * {@code catalog.freight_default_template_not_found}——freight 恒非空）
     * → 店铺卡片（店铺行缺失按 null 呈现——脏数据形态降级）。
     */
    @Override
    public ProductBuyerView getBuyerView(Long productId) {
        final Product product = productRepository.getByID(productId);
        if (product == null || product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), "商品不存在");
        }
        final Map<Long, Integer> availableBySku = inventoryFacade
                .queryAvailable(product.skusOrdered().stream().map(Sku::getId).toList())
                .stream()
                .collect(Collectors.toMap(InventoryAvailable::skuId, InventoryAvailable::available));
        return new ProductBuyerView(
                product.getId(),
                product.getShopId(),
                product.getName(),
                product.getDescription(),
                toImages(product),
                toAttributes(product),
                toSpecDimensions(product),
                toSkus(product, availableBySku),
                toFreight(product.getFreightTemplateId(), product.getShopId()),
                toShop(product.getShopId()));
    }

    /**
     * 图片引用视图（URL + 主图标记，按加入序）。
     *
     * @param product 商品聚合
     * @return 图片视图列表
     */
    private static List<ProductBuyerView.Image> toImages(Product product) {
        return product.imagesOrdered().stream()
                .map(image -> new ProductBuyerView.Image(image.getUrl(), image.isPrimary()))
                .toList();
    }

    /**
     * 自定义属性视图（按加入序）。
     *
     * @param product 商品聚合
     * @return 属性视图列表
     */
    private static List<ProductBuyerView.Attribute> toAttributes(Product product) {
        return product.attributesOrdered().stream()
                .map(attribute -> new ProductBuyerView.Attribute(attribute.getKey(), attribute.getValue()))
                .toList();
    }

    /**
     * 规格维度视图（规格选择区渲染：维度名 + 可选值，按配置序；未配置
     * 模板按空列表防御）。
     *
     * @param product 商品聚合
     * @return 维度视图列表
     */
    private static List<ProductBuyerView.SpecDimension> toSpecDimensions(Product product) {
        return product.getSpecTemplate()
                .map(template -> template.dimensionsOrdered().stream()
                        .map(dimension -> new ProductBuyerView.SpecDimension(
                                dimension.getName(), dimension.valuesOrdered()))
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * SKU 行视图（按模板展开序全量：价格 + 可售——启用 SKU 经库存读
     * zip、停用 SKU 不出售可售置 0、缺库存行按 0 置灰）。
     *
     * @param product        商品聚合
     * @param availableBySku SKU 可售映射（skuId → 可售量）
     * @return SKU 视图列表
     */
    private static List<ProductBuyerView.Sku> toSkus(Product product,
                                                    Map<Long, Integer> availableBySku) {
        return product.skusOrdered().stream()
                .map(sku -> new ProductBuyerView.Sku(
                        sku.getId(),
                        sku.getSpecHash(),
                        sku.getSpecSummary(),
                        sku.getPrice(),
                        sku.isEnabled() ? availableBySku.getOrDefault(sku.getId(), 0) : 0))
                .toList();
    }

    /**
     * 运费模板规则概要装配：绑定行存在 → 绑定概要；未绑定/绑定悬挂（模板
     * 行缺失）→ 回退 {@code findDefaultByShopId(shopId)} 概要（freight 恒非空，
     * 非空设计 §2.5）；默认模板缺失 → 防御拒绝
     * {@code catalog.freight_default_template_not_found}（开店必建故不可达，
     * 不静默降级为 null/包邮）。
     *
     * @param templateId 绑定模板 ID（可空——未绑定语义由回退承载）
     * @param shopId     归属店铺 ID（回退锚点定位）
     * @return 运费概要（恒非空）
     */
    private ProductBuyerView.Freight toFreight(Long templateId, Long shopId) {
        FreightTemplate template = null;
        if (templateId != null) {
            template = freightTemplateRepository.getByID(templateId);
        }
        if (template == null) {
            template = freightTemplateRepository.findDefaultByShopId(shopId);
        }
        if (template == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_FREIGHT_DEFAULT_TEMPLATE_NOT_FOUND.code(),
                    "店铺默认运费模板缺失");
        }
        return new ProductBuyerView.Freight(
                template.getId(),
                template.getName(),
                template.getRuleType().name(),
                template.getPerItemPrice(),
                template.getBaseFreight(),
                template.getFreeThreshold());
    }

    /**
     * 店铺卡片（店铺行缺失按 null 呈现——商品存在而店铺行缺失为脏数据
     * 形态，详情主体不阻断，店铺区降级）。
     *
     * @param shopId 店铺 ID
     * @return 店铺卡片；店铺行缺失返回 null
     */
    private ProductBuyerView.Shop toShop(Long shopId) {
        final Shop shop = shopRepository.getByID(shopId);
        if (shop == null) {
            return null;
        }
        return new ProductBuyerView.Shop(shop.getId(), shop.getName(), shop.getLogo());
    }
}
