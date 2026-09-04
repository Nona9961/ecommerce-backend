package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.ProductAttributeItem;
import com.nona.api.seller.ProductAttributeRequest;
import com.nona.api.seller.ProductDetail;
import com.nona.api.seller.ProductDraftItem;
import com.nona.api.seller.ProductDraftRequest;
import com.nona.api.seller.ProductImageItem;
import com.nona.api.seller.ProductImageRequest;
import com.nona.api.seller.SkuEnabledRequest;
import com.nona.api.seller.SkuItem;
import com.nona.api.seller.SkuPriceRequest;
import com.nona.api.seller.ShopCategoryBindRequest;
import com.nona.api.seller.ShopCategoryItem;
import com.nona.api.seller.FreightTemplateBindRequest;
import com.nona.api.seller.SpecDimensionRequest;
import com.nona.api.seller.SpecTemplateRequest;
import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.EditSensitivity;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductContent;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.BrandRepository;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.domain.catalog.repo.PlatformCategoryRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.persistence.converters.ProductSnapshotConvertor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 商家端商品草稿用例：草稿 CRUD、图片引用管理（增删/设主图）、
 * 自定义属性键值管理（增删改）与规格模板/SKU 维护（整体替换模板重建
 * SKU 集、改价、启停）编排。
 * <p>
 * 当前店铺由认证上下文定位（controller 从 ThreadContext 取租户 ID=当前店铺
 * 传入创建路径；商品归属随写门禁注入租户列）。事务边界：所有写路径在用例
 * 事务内完成「加载聚合 → 领域操作 → 变更集落库」；读路径直接查询。
 * 跨店铺商品访问按不存在呈现（404——租户过滤 fail-closed，不泄露归属）。
 * 类目/品牌引用校验（引用查询能力由平台分类/品牌仓储提供）：引用非空时目标必须存在且
 * 启用（禁用态不可挂载）；更新场景引用保持不变时保留历史归属合法
 * （域内既有商品在类目/品牌禁用后维持可见，此路径允许保留引用）。
 * 编辑分流路由（字段级审核白名单）：草稿/驳回态直改留痕；在售商品保存前
 * 投影变更敏感性——敏感字段编辑（标题/类目/品牌/SKU 价格/SKU 规格构成）
 * 转待审核（编辑内容入待审草稿位、生效内容回退旧版，不产生编辑版本行），
 * 展示类编辑（描述/详情图/自定义属性/启停）直改免审（留痕）；待审核期
 * 写面冻结（聚合守卫，驳回后回草稿可改）。
 *
 * @author nona9961
 */
@Service
public class ProductUseCase {

    /**
     * 商品仓储
     */
    private final ProductRepository productRepository;

    /**
     * 商品聚合工厂
     */
    private final ProductFactory productFactory;

    /**
     * 商品编辑版本用例（保存留痕：每次实际落库的保存追加 EDIT 版本行）
     */
    private final ProductVersionUseCase productVersionUseCase;

    /**
     * 平台分类仓储（引用存在性/启用校验）
     */
    private final PlatformCategoryRepository categoryRepository;

    /**
     * 品牌仓储（引用存在性/启用校验）
     */
    private final BrandRepository brandRepository;

    /**
     * 商品内容快照转换器（在售敏感编辑分流的生效内容快照化：编辑前
     * 留存生效内容载体，分流回退输入）
     */
    private final ProductSnapshotConvertor snapshotConvertor;

    /**
     * 店铺仓储（店铺分类绑定目标归属校验：分类必须属于商品所属店铺）
     */
    private final ShopRepository shopRepository;

    /**
     * 运费模板仓储（模板绑定目标归属校验：模板必须属于商品所属店铺）
     */
    private final FreightTemplateRepository freightTemplateRepository;

    /**
     * 构造商品草稿用例。
     *
     * @param productRepository      商品仓储
     * @param productFactory         商品工厂
     * @param productVersionUseCase  商品编辑版本用例（保存留痕）
     * @param categoryRepository     平台分类仓储
     * @param brandRepository        品牌仓储
     * @param snapshotConvertor      商品内容快照转换器（敏感编辑分流回退）
     * @param shopRepository         店铺仓储（店铺分类绑定归属校验）
     * @param freightTemplateRepository 运费模板仓储（模板绑定归属校验）
     */
    public ProductUseCase(ProductRepository productRepository,
                          ProductFactory productFactory,
                          ProductVersionUseCase productVersionUseCase,
                          PlatformCategoryRepository categoryRepository,
                          BrandRepository brandRepository,
                          ProductSnapshotConvertor snapshotConvertor,
                          ShopRepository shopRepository,
                          FreightTemplateRepository freightTemplateRepository) {
        this.productRepository = productRepository;
        this.productFactory = productFactory;
        this.productVersionUseCase = productVersionUseCase;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.snapshotConvertor = snapshotConvertor;
        this.shopRepository = shopRepository;
        this.freightTemplateRepository = freightTemplateRepository;
    }

    /**
     * 创建商品草稿：类目/品牌引用校验 → 工厂创建（ID 生成/归属定型/
     * 状态 DRAFT）→ 落库 → 基线留痕（版本号=1，EDIT 触发类型——创建
     * 即版本链起点）。
     *
     * @param shopId  当前店铺 ID（认证上下文）
     * @param request 草稿主体（名称必填；描述/类目/品牌可空）
     * @return 新建草稿详情
     */
    @Transactional
    public ProductDetail createDraft(Long shopId, ProductDraftRequest request) {
        requireReference(request.categoryId(), request.brandId());
        final Product product = productFactory.createDraft(
                shopId, request.name(), request.description(), request.categoryId(), request.brandId());
        productRepository.save(product);
        productVersionUseCase.recordEdit(product);
        return toDetail(product);
    }

    /**
     * 草稿列表（翻页；新商品在前）。
     *
     * @param shopId 当前店铺 ID（认证上下文）
     * @param query  分页参数（pageNum/pageSize 已归一化）
     * @return 分页草稿列表（概要行）
     */
    public PageResult<ProductDraftItem> listDrafts(Long shopId, PageQuery query) {
        final List<Product> products = productRepository.listByShopPaged(
                shopId, Math.toIntExact(query.offset()), query.pageSize());
        final long total = productRepository.countByShop(shopId);
        return PageResult.of(products.stream().map(ProductUseCase::toItem).toList(), total, query);
    }

    /**
     * 草稿详情（含图片与属性完整列表）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 草稿详情
     */
    public ProductDetail detail(Long productId) {
        return toDetail(requireProduct(productId));
    }

    /**
     * 更新草稿主体：名称/描述/类目/品牌整体替换；引用变更时校验新目标
     * 存在且启用（保留不变的历史归属合法）；实际落库后留痕。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   新主体
     * @return 更新后的草稿详情
     */
    @Transactional
    public ProductDetail update(Long productId, ProductDraftRequest request) {
        final Product product = requireProduct(productId);
        requireReferenceOnChange(product, request.categoryId(), request.brandId());
        persistEdit(product, () -> product.updateInfo(
                request.name(), request.description(), request.categoryId(), request.brandId()));
        return toDetail(product);
    }

    /**
     * 删除商品（物理删除：级联删图片/属性/SKU/版本行）。非草稿状态删除
     * 拒绝（完整性语义：待审/在售/已下架生命周期无删除端点，下架与注销
     * 路径属后续阶段）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     */
    @Transactional
    public void delete(Long productId) {
        final Product product = requireProduct(productId);
        if (product.getStatus() != ProductStatus.DRAFT) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅草稿商品可删除");
        }
        productRepository.deleteByID(productId);
    }

    /**
     * 添加图片引用：工厂创建 → 聚合新增（主图唯一性聚合内保证）→ 落库
     * → 留痕。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   图片 URL 与主图标记
     * @return 新建图片条目
     */
    @Transactional
    public ProductImageItem addImage(Long productId, ProductImageRequest request) {
        final Product product = requireProduct(productId);
        final ProductImage image = productFactory.createImage(
                product, request.url(), Boolean.TRUE.equals(request.primary()));
        persistEdit(product, () -> product.addImage(image));
        return toImageItem(image);
    }

    /**
     * 删除图片引用（主图被删后主图位清空——在售商品删主图拒绝；实际落库
     * 后留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param imageId   图片引用 ID（必须属于当前商品，否则 404）
     */
    @Transactional
    public void removeImage(Long productId, Long imageId) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.removeImage(imageId));
    }

    /**
     * 设置主图（清除原主图标记，目标图片设为新主图；实际落库后留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param imageId   图片引用 ID（必须属于当前商品，否则 404）
     * @return 更新后的主图条目
     */
    @Transactional
    public ProductImageItem setPrimaryImage(Long productId, Long imageId) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.setPrimaryImage(imageId));
        return toImageItem(product.getImageById(imageId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_NOT_FOUND.code(), "图片不存在")));
    }

    /**
     * 添加自定义属性：工厂创建 → 聚合新增（键唯一性聚合内保证）→ 落库
     * → 留痕。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   属性键值
     * @return 新建属性条目
     */
    @Transactional
    public ProductAttributeItem addAttribute(Long productId, ProductAttributeRequest request) {
        final Product product = requireProduct(productId);
        final ProductAttribute attribute = productFactory.createAttribute(
                product, request.key(), request.value());
        persistEdit(product, () -> product.addAttribute(attribute));
        return toAttributeItem(attribute);
    }

    /**
     * 更新自定义属性（改键保持唯一；值可空；实际落库后留痕）。
     *
     * @param productId   商品 ID（必须属于当前店铺，否则 404）
     * @param attributeId 属性 ID（必须属于当前商品，否则 404）
     * @param request     新键值
     * @return 更新后的属性条目
     */
    @Transactional
    public ProductAttributeItem updateAttribute(Long productId, Long attributeId,
                                                ProductAttributeRequest request) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.updateAttribute(attributeId, request.key(), request.value()));
        return toAttributeItem(product.getAttributeById(attributeId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_NOT_FOUND.code(), "属性不存在")));
    }

    /**
     * 删除自定义属性（实际落库后留痕）。
     *
     * @param productId   商品 ID（必须属于当前店铺，否则 404）
     * @param attributeId 属性 ID（必须属于当前商品，否则 404）
     */
    @Transactional
    public void removeAttribute(Long productId, Long attributeId) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.removeAttribute(attributeId));
    }

    /**
     * 整体替换规格模板并重建 SKU 集：请求维度表 → 值对象构造（结构校验）
     * → 聚合重建（组合匹配保留/新增/移除，上限守卫）→ 变更集落库 →
     * 留痕/分流。空 dimensions = 空模板（清空 SKU 集）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   新规格模板（整体替换；空 dimensions=清空 SKU 集）
     * @return 重建后的 SKU 集（按模板展开序）
     */
    @Transactional
    public List<SkuItem> configureSpecTemplate(Long productId, SpecTemplateRequest request) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.configureSpecTemplate(toTemplate(request)));
        return product.skusOrdered().stream().map(ProductUseCase::toSkuItem).toList();
    }

    /**
     * 更新 SKU 价格（null=清除价格复位未定价；非正数拒绝；实际落库后
     * 留痕/分流——在售改价为敏感编辑转待审核）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   新价格（价格 null=清除定价）
     * @return 更新后的 SKU 条目
     */
    @Transactional
    public SkuItem updateSkuPrice(Long productId, Long skuId, SkuPriceRequest request) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.updateSkuPrice(skuId, request.price()));
        return toSkuItem(requireSku(product, skuId));
    }

    /**
     * 切换 SKU 启用状态（实际落库后留痕/分流——启停为展示类直改免审）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   启用状态
     * @return 更新后的 SKU 条目
     */
    @Transactional
    public SkuItem setSkuEnabled(Long productId, Long skuId, SkuEnabledRequest request) {
        final Product product = requireProduct(productId);
        persistEdit(product, () -> product.setSkuEnabled(skuId, request.enabled()));
        return toSkuItem(requireSku(product, skuId));
    }

    /**
     * 提交上架：草稿 → 待审核（完整性校验由聚合 submitForReview 守卫承载，
     * 校验失败拒绝并逐项细化业务码）→ 状态迁移落库。提交无内容变化
     * （待审内容 = 草稿当前内容），不产生编辑版本行；审核结论版本行由
     * 平台侧审核用例承载。
     * <p>
     * 待审核期内容冻结：提交后本商品所有写面拒绝编辑（聚合守卫），
     * 驳回后回草稿可修改重提。跨店铺商品按不存在呈现（404——租户过滤
     * fail-closed，不泄露归属）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 提交后的商品详情（状态 PENDING_REVIEW）
     */
    @Transactional
    public ProductDetail submitForReview(Long productId) {
        final Product product = requireProduct(productId);
        product.submitForReview();
        productRepository.save(product);
        return toDetail(product);
    }

    /**
     * 手动下架编排：加载商品（租户
     * 过滤 fail-closed）→ 聚合迁移（仅 ON_SALE 可下架，守卫在聚合）→
     * 变更集落库。下架为生命周期迁移，不插编辑版本行。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 下架后的商品详情（状态 DELISTED）
     */
    @Transactional
    public ProductDetail delist(Long productId) {
        final Product product = requireProduct(productId);
        product.delist();
        productRepository.save(product);
        return toDetail(product);
    }

    /**
     * 手动重新上架编排：加载商品（租户
     * 过滤 fail-closed）→ 聚合迁移（仅 DELISTED 可上架 + 完整性防御校验，
     * 守卫在聚合）→ 变更集落库。重新上架无内容变化，不插编辑版本行。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 上架后的商品详情（状态 ON_SALE）
     */
    @Transactional
    public ProductDetail relist(Long productId) {
        final Product product = requireProduct(productId);
        product.relist();
        productRepository.save(product);
        return toDetail(product);
    }

    /**
     * 整体替换商品店铺分类绑定：加载
     * 商品（租户过滤 fail-closed）→ 绑定目标归属校验（分类必须属于商品
     * 所属店铺——Shop 聚合加载校验，跨店铺/不存在按 404 呈现）→ 聚合
     * 整体替换（守卫：待审核期/已下架态冻结、去重）→ 变更集落库（从表
     * rel 行）。分类变更为展示类编辑：在售态直改免审，草稿/驳回态直改
     * 留痕（复用 persistEdit 保存语义）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   目标分类集合（整体替换；空=清空全部绑定）
     * @return 绑定后的分类条目列表（含名称，按绑定序）
     */
    @Transactional
    public List<ShopCategoryItem> updateShopCategories(Long productId,
                                                       ShopCategoryBindRequest request) {
        final Product product = requireProduct(productId);
        final List<Long> targets = request.shopCategoryIds() == null ? List.of() : request.shopCategoryIds();
        final Shop shop = requireShopOf(product);
        for (final Long categoryId : targets) {
            if (categoryId != null && shop.getCategoryById(categoryId).isEmpty()) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NOT_FOUND.code(), "店铺分类不存在", 404);
            }
        }
        persistEdit(product, () -> product.replaceShopCategories(targets));
        return toShopCategoryItems(product, shop);
    }

    /**
     * 商品店铺分类绑定回显：加载商品
     * → 按绑定分类 ID 集合在所属店铺分类中取名称 → 条目列表（按绑定序）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 已绑定分类条目列表；无绑定为空列表
     */
    public List<ShopCategoryItem> listShopCategories(Long productId) {
        final Product product = requireProduct(productId);
        final List<Long> boundIds = product.shopCategoryIdsOrdered();
        if (boundIds.isEmpty()) {
            return List.of();
        }
        return toShopCategoryItems(product, requireShopOf(product));
    }

    /**
     * 商品运费模板绑定/解绑：加载商品
     * （租户过滤 fail-closed）→ 目标模板归属校验（模板必须存在且属于商品
     * 所属店铺，跨店铺/不存在按 404 呈现；停用模板允许绑定）→ 聚合绑定
     * （守卫：待审核期/已下架态冻结）→ 变更集落库。绑定属运营配置：在售
     * 态直改免审，草稿/驳回态直改留痕（复用 persistEdit 保存语义）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   目标模板 ID（null=解绑）
     */
    @Transactional
    public void bindFreightTemplate(Long productId, FreightTemplateBindRequest request) {
        final Product product = requireProduct(productId);
        final Long templateId = request.freightTemplateId();
        if (templateId != null) {
            final FreightTemplate template = freightTemplateRepository.getByID(templateId);
            if (template == null) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_NOT_FOUND.code(), "运费模板不存在", 404);
            }
        }
        persistEdit(product, () -> product.bindFreightTemplate(templateId));
    }

    /**
     * 加载商品所属店铺并断言存在（店铺分类绑定校验/名称映射载体：商品
     * 归属店铺行必在——行缺失为数据不一致异常态，按不存在呈现 404）。
     *
     * @param product 商品聚合
     * @return 店铺聚合
     */
    private Shop requireShopOf(Product product) {
        final Shop shop = shopRepository.getByID(product.getShopId());
        if (shop == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_SHOP_NOT_FOUND.code(), "店铺不存在", 404);
        }
        return shop;
    }

    /**
     * 绑定分类条目列表（按绑定序，含名称与排序）：名称映射经商品所属
     * 店铺分类集合——绑定目标在绑定前已校验归属（必在集合内）；历史
     * 悬挂引用（引用目标已失效的异常形态）按不存在跳过，回显只列有效
     * 绑定。
     *
     * @param product 商品聚合
     * @param shop    商品所属店铺聚合
     * @return 分类条目列表；无绑定为空列表
     */
    private static List<ShopCategoryItem> toShopCategoryItems(Product product, Shop shop) {
        return product.shopCategoryIdsOrdered().stream()
                .map(shop::getCategoryById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(ProductUseCase::toShopCategoryItem)
                .toList();
    }

    /**
     * 领域店铺分类 → 契约条目。
     *
     * @param category 店铺分类
     * @return 契约条目
     */
    private static ShopCategoryItem toShopCategoryItem(ShopCategory category) {
        return new ShopCategoryItem(category.getId(), category.getName(), category.getOrder());
    }

    /**
     * 保存路径统一编排（编辑分流 + 落库 + 留痕）：执行领域编辑后，在售
     * 商品按变更敏感性路由（敏感字段编辑 → 待审草稿位装载 + 生效内容
     * 回退 + 转待审核，不插编辑版本行；展示类编辑 → 直改留痕）；草稿/
     * 驳回态直改留痕。保存仅在变更集非空时落库（不重复留痕）。
     *
     * @param product 已加载的商品聚合（变更追踪已登记）
     * @param edit    领域编辑操作（聚合写方法；写面冻结守卫在聚合内）
     */
    private void persistEdit(Product product, Runnable edit) {
        final ProductContent effectiveBefore = product.getStatus() == ProductStatus.ON_SALE
                ? captureEffectiveBefore(product) : null;
        edit.run();
        final boolean routed = routeSensitiveEdit(product, effectiveBefore);
        if (productRepository.save(product) && !routed) {
            productVersionUseCase.recordEdit(product);
        }
    }

    /**
     * 在售商品编辑分流路由：保存前投影变更敏感性——敏感字段编辑（标题/
     * 类目/品牌/SKU 价格/SKU 规格构成）转待审核（编辑内容入待审草稿位、
     * 生效内容回退编辑前旧版，买家继续可见旧内容直至平台裁定）；展示类
     * 编辑不路由（直改免审）。
     *
     * @param product        已发生领域编辑的聚合
     * @param effectiveBefore 编辑前生效内容载体（在售分流回退输入；非在售
     *                        路径传 null 不路由）
     * @return 已转待审核分流返回 true（调用方跳过编辑版本行留痕）
     */
    private boolean routeSensitiveEdit(Product product, ProductContent effectiveBefore) {
        if (product.getStatus() != ProductStatus.ON_SALE) {
            return false;
        }
        final EditSensitivity sensitivity = productRepository.summarizeSensitiveEdit(product);
        if (sensitivity != EditSensitivity.SENSITIVE) {
            return false;
        }
        product.stageSensitiveEdit(effectiveBefore);
        return true;
    }

    /**
     * 编辑前生效内容快照（在售敏感编辑回退输入）：聚合当前全部内容经
     * 快照中间形态往返重建独立载体（与聚合内子实体引用解耦，后续编辑
     * 不影响回退载体）。
     *
     * @param product 商品聚合（编辑前状态）
     * @return 生效内容载体
     */
    private ProductContent captureEffectiveBefore(Product product) {
        return snapshotConvertor.toContent(snapshotConvertor.toSnapshotJson(product));
    }

    /**
     * 请求维度表 → 规格模板值对象（空 dimensions 构造空模板；结构校验
     * 由值对象构造路径承载——维度名非空/唯一、值非空/值内唯一）。
     *
     * @param request 请求体
     * @return 规格模板
     */
    private static SpecTemplate toTemplate(SpecTemplateRequest request) {
        final List<SpecItem> dimensions = request.dimensions().stream()
                .map(ProductUseCase::toSpecItem)
                .toList();
        return new SpecTemplate(dimensions);
    }

    /**
     * 请求维度 → 规格维度值对象（与 {@link #toTemplate} 配合）。
     *
     * @param dimension 请求维度
     * @return 规格维度
     */
    private static SpecItem toSpecItem(SpecDimensionRequest dimension) {
        return new SpecItem(dimension.name(), dimension.values());
    }

    /**
     * 按 ID 定位 SKU 并断言存在（改价/启停的目标必须属于当前商品；
     * 不属于按不存在呈现，不泄露归属）。
     *
     * @param product 商品聚合
     * @param skuId   SKU ID
     * @return SKU
     */
    private static Sku requireSku(Product product, Long skuId) {
        return product.getSkuById(skuId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SKU_NOT_FOUND.code(), "SKU不存在"));
    }

    /**
     * 领域 SKU → 契约条目。
     *
     * @param sku SKU 实体
     * @return 契约条目
     */
    private static SkuItem toSkuItem(Sku sku) {
        return new SkuItem(sku.getId(), sku.getSpecHash(), sku.getSpecSummary(),
                sku.getPrice(), sku.isEnabled());
    }

    /**
     * 创建/更新引用校验：引用非空时目标必须存在且启用
     * （禁用态不可挂载——「新商品不能挂」语义）。
     *
     * @param categoryId 平台类目 ID（可空）
     * @param brandId    品牌 ID（可空）
     */
    private void requireReference(Long categoryId, Long brandId) {
        requireCategoryReference(categoryId);
        requireBrandReference(brandId);
    }

    /**
     * 更新场景引用校验：仅当引用值发生变更时校验新目标（保留不变的历史
     * 归属合法——既有商品在类目/品牌禁用后保持引用不变）。
     *
     * @param product      当前商品
     * @param newCategoryId 新类目 ID（可空=清空）
     * @param newBrandId    新品牌 ID（可空=清空）
     */
    private void requireReferenceOnChange(Product product, Long newCategoryId, Long newBrandId) {
        if (!Objects.equals(product.getCategoryId(), newCategoryId)) {
            requireCategoryReference(newCategoryId);
        }
        if (!Objects.equals(product.getBrandId(), newBrandId)) {
            requireBrandReference(newBrandId);
        }
    }

    /**
     * 平台类目引用校验：存在且启用（不存在 404；禁用 400）。
     *
     * @param categoryId 类目 ID（可空）
     */
    private void requireCategoryReference(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        final PlatformCategory category = categoryRepository.getByID(categoryId);
        if (category == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NOT_FOUND.code(), "平台分类不存在");
        }
        if (category.getStatus() != CategoryStatus.ENABLED) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_DISABLED.code(), "平台分类已禁用，不可挂载");
        }
    }

    /**
     * 品牌引用校验：存在且启用（不存在 404；禁用 400）。
     *
     * @param brandId 品牌 ID（可空）
     */
    private void requireBrandReference(Long brandId) {
        if (brandId == null) {
            return;
        }
        final Brand brand = brandRepository.getByID(brandId);
        if (brand == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NOT_FOUND.code(), "品牌不存在");
        }
        if (brand.getStatus() != BrandStatus.ENABLED) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_DISABLED.code(), "品牌已禁用，不可挂载");
        }
    }

    /**
     * 加载商品并断言存在（租户过滤 fail-closed：跨店铺商品按不存在呈现）。
     *
     * @param productId 商品 ID
     * @return 商品聚合
     */
    private Product requireProduct(Long productId) {
        final Product product = productRepository.getByID(productId);
        if (product == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), "商品不存在");
        }
        return product;
    }

    /**
     * 领域商品 → 契约详情。
     *
     * @param product 商品聚合
     * @return 契约详情
     */
    private static ProductDetail toDetail(Product product) {
        return new ProductDetail(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getBrandId(),
                product.getStatus().name(),
                product.imagesOrdered().stream().map(ProductUseCase::toImageItem).toList(),
                product.attributesOrdered().stream().map(ProductUseCase::toAttributeItem).toList());
    }

    /**
     * 领域商品 → 契约列表项。
     *
     * @param product 商品聚合
     * @return 契约列表项
     */
    private static ProductDraftItem toItem(Product product) {
        return new ProductDraftItem(
                product.getId(),
                product.getName(),
                product.getStatus().name(),
                product.getCategoryId(),
                product.getBrandId(),
                product.imagesOrdered().size(),
                product.attributesOrdered().size());
    }

    /**
     * 领域图片引用 → 契约条目。
     *
     * @param image 图片引用
     * @return 契约条目
     */
    private static ProductImageItem toImageItem(ProductImage image) {
        return new ProductImageItem(image.getId(), image.getUrl(), image.isPrimary());
    }

    /**
     * 领域属性 → 契约条目。
     *
     * @param attribute 属性
     * @return 契约条目
     */
    private static ProductAttributeItem toAttributeItem(ProductAttribute attribute) {
        return new ProductAttributeItem(attribute.getId(), attribute.getKey(), attribute.getValue());
    }
}