package com.nona.application.seller;

import com.nona.api.seller.ProductAttributeItem;
import com.nona.api.seller.ProductAttributeRequest;
import com.nona.api.seller.ProductDetail;
import com.nona.api.seller.ProductDraftRequest;
import com.nona.api.seller.ProductImageRequest;
import com.nona.api.seller.SkuEnabledRequest;
import com.nona.api.seller.SkuItem;
import com.nona.api.seller.SkuPriceRequest;
import com.nona.api.seller.SpecDimensionRequest;
import com.nona.api.seller.SpecTemplateRequest;
import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.domain.catalog.factory.ProductEditVersionFactory;
import com.nona.domain.catalog.repo.ProductEditVersionRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.BrandPO;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品审核流卖家侧接线集成测试：提交上架、完整性校验失败拒绝、
 * 字段级编辑分流（在售改价转待审且生效内容保持旧价、待审不插编辑版本行）
 * 与展示字段直改不回归（绿色底座断言）、待审期冻结、在售回滚分流、非草稿
 * 删除拒绝。
 * <p>
 * 红状态说明：提交用例（submitForReview）与编辑分流编排为设计契约（方法
 * 体抛 UnsupportedOperationException）——依赖用例红因 = UOE；写面冻结/
 * 删除守卫/在售分流为既有路径未接线的新行为——红因 = 断言失败（行为缺
 * 失）。展示字段直改路径为既有行为（绿色底座，不回归）。
 * <p>
 * 数据装配：在售/待审商品经聚合保存直插（商户请求上下文模拟），绕过
 * 审核用例的未实现态。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductReviewSubmitIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final long SHOP_A = 9101L;

    /**
     * 商家 A 账号 ID（操作人与请求身份）
     */
    private static final String OPERATOR_A = "72001";

    /**
     * 商品写路径用例（提交入口）
     */
    @Autowired
    private ProductUseCase productUseCase;

    /**
     * 商品编辑版本用例（回滚路径）
     */
    @Autowired
    private ProductVersionUseCase productVersionUseCase;

    /**
     * 商品仓储（在售/待审测试数据直插与读回）
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * 编辑版本仓储（回滚素材版本行直插）
     */
    @Autowired
    private ProductEditVersionRepository editVersionRepository;

    /**
     * 编辑版本工厂（版本行直插构造）
     */
    @Autowired
    private ProductEditVersionFactory editVersionFactory;

    /**
     * 商品主表 JPA（状态断言与清理）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 版本子表 JPA（版本行断言与清理）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 商品属性子表 JPA（清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 图片子表 JPA（清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * SKU 子表 JPA（清理）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

    /**
     * 启用态平台分类 ID（类目引用校验前置物）
     */
    private static final long CATEGORY_ENABLED = 93001L;

    /**
     * 启用态品牌 ID（品牌引用校验前置物）
     */
    private static final long BRAND_ENABLED = 94001L;

    /**
     * 平台分类 JPA（直插启用态引用）
     */
    @Autowired
    private PlatformCategoryJpaRepository categoryJpaRepository;

    /**
     * 品牌 JPA（直插启用态引用）
     */
    @Autowired
    private BrandJpaRepository brandJpaRepository;


    /**
     * 提权工具（测试数据清理）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 编程式事务模板（数据直插的事务边界）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 每用例前：提权清空商品系表与类目/品牌引用表 + 直插启用态引用
     * + 建立请求作用域 + 商家 A 租户与身份上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
        });
        categoryJpaRepository.deleteAll();
        brandJpaRepository.deleteAll();
        categoryJpaRepository.save(categoryPo(CATEGORY_ENABLED));
        brandJpaRepository.save(brandPo(BRAND_ENABLED));
    }

    // ---- Happy path ----

    /**
     * happy：完整草稿经用例提交 → 待审核（状态落主表）。
     */
    @Test
    @DisplayName("完整草稿用例提交转待审核")
    void submit_completeDraft_pendingReview() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = buildCompleteDraftViaUseCase();

            final ProductDetail detail = productUseCase.submitForReview(productId);

            assertThat(detail.status()).isEqualTo(ProductStatus.PENDING_REVIEW.name());
            assertThat(productJpaRepository.findById(productId).orElseThrow().getStatus())
                    .isEqualTo(ProductStatus.PENDING_REVIEW);
    
        });
}

    /**
     * happy：展示字段编辑（自定义属性改值）在售直改——状态不变 + 编辑版本
     * 行追加（免审直改既有行为，不回归）。
     */
    @Test
    @DisplayName("在售展示字段编辑直改生效且留痕")
    void attributeEdit_onSale_directEffective() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = insertProduct(SHOP_A, ProductStatus.ON_SALE);
            final long attributeId = productRepository.getByID(productId)
                    .attributesOrdered().get(0).getId();
            final long versionCountBefore = editVersionJpaRepository.countByProductId(productId);

            final ProductAttributeItem item = productUseCase.updateAttribute(
                    productId, attributeId, new ProductAttributeRequest("材质", "新棉"));

            assertThat(productRepository.getByID(productId).getStatus())
                    .isEqualTo(ProductStatus.ON_SALE);
            assertThat(productRepository.getByID(productId)
                    .getAttributeById(attributeId).orElseThrow().getValue()).isEqualTo("新棉");
            assertThat(editVersionJpaRepository.countByProductId(productId)).isEqualTo(versionCountBefore + 1);
            assertThat(item.id()).isEqualTo(attributeId);
    
        });
}

    // ---- Critical path ----

    /**
     * critical：提交完整性校验失败拒绝——缺平台类目/品牌草稿提交报业务码
     * （模板/定价/启用/主图已补全，仅缺类目品牌，指向类目校验）。
     */
    @Test
    @DisplayName("缺类目品牌草稿提交拒绝")
    void submit_incomplete_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final ProductDetail draft = productUseCase.createDraft(SHOP_A,
                    new ProductDraftRequest("不完整商品", "描述", null, null));
            final List<SkuItem> skus = productUseCase.configureSpecTemplate(draft.id(),
                    new SpecTemplateRequest(List.of(new SpecDimensionRequest("颜色", List.of("黑")))));
            productUseCase.updateSkuPrice(draft.id(), skus.get(0).id(), new SkuPriceRequest(1999L));
            productUseCase.setSkuEnabled(draft.id(), skus.get(0).id(), new SkuEnabledRequest(true));
            productUseCase.addImage(draft.id(), new ProductImageRequest("/files/a.png", true));

            assertThatThrownBy(() -> productUseCase.submitForReview(draft.id()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_CATEGORY_REQUIRED.code()));
    
        });
}

    /**
     * critical：在售商品敏感字段编辑（改价）→ 转待审核且生效内容保持旧价
     * （买家继续可见旧版；不插编辑版本行）。
     */
    @Test
    @DisplayName("在售改价转待审且生效内容保持旧价")
    void priceEdit_onSale_sensitiveRouting() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = insertProduct(SHOP_A, ProductStatus.ON_SALE);
            final Product before = productRepository.getByID(productId);
            final Long skuId = before.skusOrdered().get(0).getId();
            final long oldPrice = before.getSkuById(skuId).orElseThrow().getPrice();

            productUseCase.updateSkuPrice(productId, skuId, new SkuPriceRequest(999L));

            final Product reloaded = productRepository.getByID(productId);
            assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(reloaded.getSkuById(skuId).orElseThrow().getPrice()).isEqualTo(oldPrice);
            assertThat(editVersionJpaRepository
                    .findByProductIdOrderByVersionNoDesc(productId, PageRequest.of(0, 100))
                    .getContent())
                    .noneMatch(v -> v.getTriggerType() == EditVersionTriggerType.EDIT);
    
        });
}

    /**
     * critical：待审期内编辑拒绝（提交冻结内容——待审核商品改价报编辑冻结
     * 业务码）。
     */
    @Test
    @DisplayName("待审期改价拒绝")
    void pendingReview_priceEdit_forbidden() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = insertProduct(SHOP_A, ProductStatus.PENDING_REVIEW);
            final Long skuId = productRepository.getByID(productId)
                    .skusOrdered().get(0).getId();

            assertThatThrownBy(() -> productUseCase.updateSkuPrice(productId, skuId, new SkuPriceRequest(999L)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_EDIT_FORBIDDEN.code()));
    
        });
}

    /**
     * critical：在售商品回滚历史版本（内容重置含敏感要素）→ 转待审核分流
     * （生效内容不直接回滚）。
     */
    @Test
    @DisplayName("在售回滚转待审分流")
    void rollback_onSale_routedToReview() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = insertProductWithBaselineVersion(SHOP_A, ProductStatus.ON_SALE);

            final ProductDetail detail = productVersionUseCase.rollback(productId, 1);

            assertThat(detail.status()).isEqualTo(ProductStatus.PENDING_REVIEW.name());
    
        });
}

    /**
     * critical：非草稿商品删除拒绝（在售/待审无删除端点——生命周期完整性）。
     */
    @Test
    @DisplayName("在售商品删除拒绝")
    void delete_onSale_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final long productId = insertProduct(SHOP_A, ProductStatus.ON_SALE);

            assertThatThrownBy(() -> productUseCase.delete(productId))
                    .isInstanceOf(BusinessException.class);
            assertThat(productJpaRepository.existsById(productId)).isTrue();
    
        });
}

    // ---- 构建辅助 ----

    /**
     * 经用例构建完整草稿（主体 + 主图 + 模板 + 双 SKU 定价启用）。
     *
     * @return 草稿 ID
     */
    private long buildCompleteDraftViaUseCase() {
        final ProductDetail draft = productUseCase.createDraft(SHOP_A,
                new ProductDraftRequest("完整商品", "描述", CATEGORY_ENABLED, BRAND_ENABLED));
        final List<SkuItem> skus = productUseCase.configureSpecTemplate(draft.id(),
                new SpecTemplateRequest(List.of(new SpecDimensionRequest("颜色", List.of("黑", "白")))));
        productUseCase.updateSkuPrice(draft.id(), skus.get(0).id(), new SkuPriceRequest(1999L));
        productUseCase.updateSkuPrice(draft.id(), skus.get(1).id(), new SkuPriceRequest(2999L));
        productUseCase.setSkuEnabled(draft.id(), skus.get(0).id(), new SkuEnabledRequest(true));
        productUseCase.setSkuEnabled(draft.id(), skus.get(1).id(), new SkuEnabledRequest(true));
        productUseCase.addImage(draft.id(), new ProductImageRequest("/files/a.png", true));
        return draft.id();
    }

    /**
     * 直插指定状态完整商品（主体 + 主图 + 属性 + 模板 + 双 SKU 定价启用）：
     * 以合法状态机迁移装配目标状态（构造 DRAFT 完整内容 → 提交转待审核 →
     * 通过转在售）——非草稿形态不能经编辑方法装配（写面冻结守卫），
     * 待审/在售内容只能由草稿经审核流到达。
     *
     * @param shopId 店铺 ID（租户）
     * @param status 商品状态（DRAFT / PENDING_REVIEW / ON_SALE）
     * @return 商品 ID
     */
    private long insertProduct(long shopId, ProductStatus status) {
        return tx.execute(t -> {
            final Product product = new Product(IDUtils.generateID(), shopId,
                    "直插商品", "描述", CATEGORY_ENABLED, BRAND_ENABLED, ProductStatus.DRAFT);
            product.addImage(new ProductImage(IDUtils.generateID(), product.getId(), "/files/a.png", true));
            product.addAttribute(new ProductAttribute(IDUtils.generateID(), product.getId(), "材质", "纯棉"));
            product.configureSpecTemplate(new SpecTemplate(
                    List.of(new SpecItem("颜色", List.of("黑", "白")))));
            priceAndEnableAll(product);
            if (status == ProductStatus.PENDING_REVIEW) {
                product.submitForReview();
            } else if (status == ProductStatus.ON_SALE) {
                product.submitForReview();
                product.approve();
            }
            productRepository.save(product);
            return product.getId();
        });
    }

    /**
     * 直插指定状态商品 + 基线编辑版本行（回滚素材）：基线为与当前内容
     * 不同的完整历史形态（仅标题不同——回滚命中敏感字段分流；完整内容
     * 保证回滚后的待审内容通过完整性校验）。
     *
     * @param shopId 店铺 ID（租户）
     * @param status 商品状态
     * @return 商品 ID
     */
    private long insertProductWithBaselineVersion(long shopId, ProductStatus status) {
        return tx.execute(t -> {
            final long productId = insertProduct(shopId, status);
            editVersionRepository.append(editVersionFactory.createEdit(
                    productId, 1,
                    "{\"name\":\"直插商品v1\",\"description\":\"描述\","
                            + "\"categoryId\":" + CATEGORY_ENABLED + ",\"brandId\":" + BRAND_ENABLED + ","
                            + "\"images\":[{\"id\":996001,\"url\":\"/files/a.png\",\"primary\":true}],"
                            + "\"attributes\":[{\"id\":995001,\"key\":\"材质\",\"value\":\"纯棉\"}],"
                            + "\"specTemplate\":[{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]}],"
                            + "\"skus\":[{\"id\":997001,\"specHash\":\"h1\",\"specSummary\":\"颜色:黑\","
                            + "\"price\":1999,\"enabled\":true},{\"id\":997002,\"specHash\":\"h2\","
                            + "\"specSummary\":\"颜色:白\",\"price\":2999,\"enabled\":true}]}",
                    OPERATOR_A));
            return productId;
        });
    }

    /**
     * 全部 SKU 定价并启用（直插构建用；价格 1999/2999）。
     *
     * @param product 商品聚合
     */
    private static void priceAndEnableAll(Product product) {
        final List<Sku> skus = product.skusOrdered();
        product.updateSkuPrice(skus.get(0).getId(), 1999L);
        product.updateSkuPrice(skus.get(1).getId(), 2999L);
        product.setSkuEnabled(skus.get(0).getId(), true);
        product.setSkuEnabled(skus.get(1).getId(), true);
    }

    /**
     * 构造启用态平台分类行。
     *
     * @param id 分类 ID
     * @return 分类 PO
     */
    private static PlatformCategoryPO categoryPo(long id) {
        final PlatformCategoryPO po = new PlatformCategoryPO();
        po.setId(id);
        po.setName("数码");
        po.setOrderNo(1);
        po.setStatus(CategoryStatus.ENABLED);
        return po;
    }

    /**
     * 构造启用态品牌行。
     *
     * @param id 品牌 ID
     * @return 品牌 PO
     */
    private static BrandPO brandPo(long id) {
        final BrandPO po = new BrandPO();
        po.setId(id);
        po.setName("示例");
        po.setStatus(BrandStatus.ENABLED);
        return po;
    }
}