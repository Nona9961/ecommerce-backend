package com.nona.application.seller;

import com.nona.api.seller.ShopCategoryItem;
import com.nona.api.seller.ShopCategoryRequest;
import com.nona.api.seller.ShopDetail;
import com.nona.api.seller.ShopInfoRequest;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.factory.ShopFactory;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商家端店铺用例：店铺信息查询/编辑与店铺分类 CRUD 编排。
 * <p>
 * 当前店铺由认证上下文定位（controller 从 ThreadContext 取租户 ID=当前店铺
 * 传入），本用例不感知 token 机制。事务边界：所有写路径（编辑/分类增删改）
 * 在用例事务内完成「加载聚合 → 领域操作 → 变更集落库」；读路径直接查询。
 * 店铺状态不接受商家编辑（编辑方法不触碰状态字段）；分类删除为物理删除，
 * 商品引用守卫属商品域后续阶段职责。
 *
 * @author nona9961
 */
@Service
public class ShopUseCase {

    /**
     * 店铺仓储
     */
    private final ShopRepository shopRepository;

    /**
     * 店铺聚合工厂
     */
    private final ShopFactory shopFactory;

    /**
     * 商品仓储（分类删除引用守卫：删除前须零商品绑定——引用查询租户
     * 过滤内同店）
     */
    private final ProductRepository productRepository;

    /**
     * 构造店铺用例。
     *
     * @param shopRepository    店铺仓储
     * @param shopFactory       店铺聚合工厂
     * @param productRepository 商品仓储（分类删除引用守卫）
     */
    public ShopUseCase(ShopRepository shopRepository, ShopFactory shopFactory,
                       ProductRepository productRepository) {
        this.shopRepository = shopRepository;
        this.shopFactory = shopFactory;
        this.productRepository = productRepository;
    }

    /**
     * 当前店铺详情（含分类列表，按展示排序升序）。
     *
     * @param shopId 当前店铺 ID（认证上下文）
     * @return 店铺详情
     */
    public ShopDetail detail(Long shopId) {
        final Shop shop = requireShop(shopId);
        return toDetail(shop);
    }

    /**
     * 编辑店铺信息：名称必填非空（领域校验兜底），状态不变。
     *
     * @param shopId  当前店铺 ID（认证上下文）
     * @param request 店铺信息
     * @return 更新后的店铺详情
     */
    @Transactional
    public ShopDetail updateInfo(Long shopId, ShopInfoRequest request) {
        final Shop shop = requireShop(shopId);
        shop.updateInfo(request.name(), request.logo(), request.description());
        shopRepository.save(shop);
        return toDetail(shop);
    }

    /**
     * 新增店铺分类：工厂创建 → 聚合新增（排序自动分配）→ 落库。
     *
     * @param shopId  当前店铺 ID（认证上下文）
     * @param request 分类名称
     * @return 新建分类（含分配的排序）
     */
    @Transactional
    public ShopCategoryItem addCategory(Long shopId, ShopCategoryRequest request) {
        final Shop shop = requireShop(shopId);
        final ShopCategory category = shopFactory.createCategory(shop, request.name());
        shop.addCategory(category);
        shopRepository.save(shop);
        return toItem(category);
    }

    /**
     * 店铺分类改名：目标必须属于当前店铺（否则按不存在呈现，404）。
     *
     * @param shopId     当前店铺 ID（认证上下文）
     * @param categoryId 分类 ID
     * @param request    新名称
     * @return 更新后的分类
     */
    @Transactional
    public ShopCategoryItem renameCategory(Long shopId, Long categoryId, ShopCategoryRequest request) {
        final Shop shop = requireShop(shopId);
        shop.renameCategory(categoryId, request.name());
        shopRepository.save(shop);
        return toItem(shop.getCategoryById(categoryId).orElseThrow(() ->
                new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NOT_FOUND.code(),
                        "店铺分类不存在", 404)));
    }

    /**
     * 删除店铺分类（物理删除；其余分类排序不重排）。
     * <p>
     * 引用守卫（S7.1）：删除前分类必须零商品绑定——存在商品引用时按
     * 冲突拒绝（{@code CATALOG_SHOP_CATEGORY_IN_USE} 409，商家先解绑
     * 再删）；无绑定删除语义与既有一致。目标分类必须属于当前店铺
     * （否则按不存在呈现 404）。
     *
     * @param shopId     当前店铺 ID（认证上下文）
     * @param categoryId 分类 ID（必须属于当前店铺，否则 404）
     */
    @Transactional
    public void removeCategory(Long shopId, Long categoryId) {
        final Shop shop = requireShop(shopId);
        if (shop.getCategoryById(categoryId).isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NOT_FOUND.code(),
                    "店铺分类不存在", 404);
        }
        if (productRepository.existsProductBoundToShopCategory(categoryId)) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_IN_USE.code(),
                    "店铺分类已被商品引用，请先解除绑定");
        }
        shop.removeCategory(categoryId);
        shopRepository.save(shop);
    }

    /**
     * 加载当前店铺并断言存在（认证上下文指向的店铺必须存在；
     * 店铺缺失为数据不一致异常态，按 404 呈现）。
     *
     * @param shopId 店铺 ID
     * @return 店铺聚合
     */
    private Shop requireShop(Long shopId) {
        final Shop shop = shopRepository.getByID(shopId);
        if (shop == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_NOT_FOUND.code(), "店铺不存在");
        }
        return shop;
    }

    /**
     * 领域店铺 → 契约店铺详情。
     *
     * @param shop 店铺聚合
     * @return 店铺详情
     */
    private static ShopDetail toDetail(Shop shop) {
        return new ShopDetail(shop.getId(), shop.getName(), shop.getLogo(), shop.getDescription(),
                shop.getStatus().name(), toItems(shop.categoriesOrdered()));
    }

    /**
     * 领域分类列表 → 契约分类条目列表。
     *
     * @param categories 领域分类
     * @return 契约分类条目
     */
    private static List<ShopCategoryItem> toItems(List<ShopCategory> categories) {
        return categories.stream().map(ShopUseCase::toItem).toList();
    }

    /**
     * 领域分类 → 契约分类条目。
     *
     * @param category 领域分类
     * @return 契约分类条目
     */
    private static ShopCategoryItem toItem(ShopCategory category) {
        return new ShopCategoryItem(category.getId(), category.getName(), category.getOrder());
    }
}