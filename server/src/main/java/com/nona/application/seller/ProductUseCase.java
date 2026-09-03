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
import com.nona.api.seller.SpecDimensionRequest;
import com.nona.api.seller.SpecTemplateRequest;
import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.BrandRepository;
import com.nona.domain.catalog.repo.PlatformCategoryRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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
 * 草稿状态恒 DRAFT（可保存不生效——发布/审核链路属后续阶段）。
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
     * 构造商品草稿用例。
     *
     * @param productRepository      商品仓储
     * @param productFactory         商品工厂
     * @param productVersionUseCase  商品编辑版本用例（保存留痕）
     * @param categoryRepository     平台分类仓储
     * @param brandRepository        品牌仓储
     */
    public ProductUseCase(ProductRepository productRepository,
                          ProductFactory productFactory,
                          ProductVersionUseCase productVersionUseCase,
                          PlatformCategoryRepository categoryRepository,
                          BrandRepository brandRepository) {
        this.productRepository = productRepository;
        this.productFactory = productFactory;
        this.productVersionUseCase = productVersionUseCase;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
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
        product.updateInfo(request.name(), request.description(), request.categoryId(), request.brandId());
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
        return toDetail(product);
    }

    /**
     * 删除草稿（物理删除：级联删图片/属性引用行）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     */
    @Transactional
    public void delete(Long productId) {
        requireProduct(productId);
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
        product.addImage(image);
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
        return toImageItem(image);
    }

    /**
     * 删除图片引用（主图被删后主图位清空；实际落库后留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param imageId   图片引用 ID（必须属于当前商品，否则 404）
     */
    @Transactional
    public void removeImage(Long productId, Long imageId) {
        final Product product = requireProduct(productId);
        product.removeImage(imageId);
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
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
        product.setPrimaryImage(imageId);
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
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
        product.addAttribute(attribute);
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
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
        product.updateAttribute(attributeId, request.key(), request.value());
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
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
        product.removeAttribute(attributeId);
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
    }

    /**
     * 整体替换规格模板并重建 SKU 集：请求维度表 → 值对象构造（结构校验）
     * → 聚合重建（组合匹配保留/新增/移除，上限守卫）→ 变更集落库 →
     * 留痕。空 dimensions = 空模板（清空 SKU 集）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   新规格模板（整体替换；空 dimensions=清空 SKU 集）
     * @return 重建后的 SKU 集（按模板展开序）
     */
    @Transactional
    public List<SkuItem> configureSpecTemplate(Long productId, SpecTemplateRequest request) {
        final Product product = requireProduct(productId);
        product.configureSpecTemplate(toTemplate(request));
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
        return product.skusOrdered().stream().map(ProductUseCase::toSkuItem).toList();
    }

    /**
     * 更新 SKU 价格（null=清除价格复位未定价；非正数拒绝；实际落库后
     * 留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   新价格（价格 null=清除定价）
     * @return 更新后的 SKU 条目
     */
    @Transactional
    public SkuItem updateSkuPrice(Long productId, Long skuId, SkuPriceRequest request) {
        final Product product = requireProduct(productId);
        product.updateSkuPrice(skuId, request.price());
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
        return toSkuItem(requireSku(product, skuId));
    }

    /**
     * 切换 SKU 启用状态（实际落库后留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   启用状态
     * @return 更新后的 SKU 条目
     */
    @Transactional
    public SkuItem setSkuEnabled(Long productId, Long skuId, SkuEnabledRequest request) {
        final Product product = requireProduct(productId);
        product.setSkuEnabled(skuId, request.enabled());
        if (productRepository.save(product)) {
            productVersionUseCase.recordEdit(product);
        }
        return toSkuItem(requireSku(product, skuId));
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