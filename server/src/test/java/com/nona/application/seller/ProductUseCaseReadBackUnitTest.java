package com.nona.application.seller;

import com.nona.api.seller.FreightTemplateBindRequest;
import com.nona.api.seller.SkuItem;
import com.nona.api.seller.SpecDimensionRequest;
import com.nona.api.seller.SpecTemplateRequest;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.domain.catalog.repo.BrandRepository;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.domain.catalog.repo.PlatformCategoryRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.persistence.converters.ProductSnapshotConvertor;
import com.nona.inf.replica.LastWriteMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 商家端商品编辑回显 3 只读方法场景测试（WU-47 前端约定回显 GET：SKU
 * 集 / 规格模板 / 运费模板绑定，WU-60 红阶段契约；ProductUseCase 只读
 * 追加，ProductAggregate 零改动）。
 * <p>
 * 覆盖：happy——SKU 集回显（价格分透传 + 启用/规格摘要）、规格模板回显
 * （维度按配置序）、运费绑定回显（非空绑定）；critical——未配置模板
 * （空维度列表非 null，非空设计）、未绑定运费（null = 解绑语义）、无
 * SKU 空列表；fail——商品不存在/跨店铺（租户过滤）按 404 呈现。
 * <p>
 * 依赖装配：ProductUseCase 全部依赖（10 项：仓储/工厂/版本用例/快照
 * 转换器/写后埋点/上下文）以 mock 承载（@BeforeEach 重建被测用例，
 * 桩逐用例布置全部被使用——只读方法零事务零埋点桩）。红阶段失败
 * 原因 = 实现缺失（方法体 UOE），而非语法/装配错误。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ProductUseCaseReadBackUnitTest {

    /**
     * 测试商品 / 店铺 / 运费模板
     */
    private static final long PRODUCT = 2001L;
    private static final long SHOP_A = 4001L;
    private static final long FREIGHT_TEMPLATE = 7001L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductFactory productFactory;

    @Mock
    private ProductVersionUseCase productVersionUseCase;

    @Mock
    private PlatformCategoryRepository categoryRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductSnapshotConvertor snapshotConvertor;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private LastWriteMarker lastWriteMarker;

    @Mock
    private TenantContextAccessor tenantContextAccessor;

    /**
     * 被测用例（红阶段不注册 Spring；依赖全 mock，setUp 装配）
     */
    private ProductUseCase useCase;

    /**
     * 每用例前重建被测用例。
     */
    @BeforeEach
    void setUp() {
        useCase = new ProductUseCase(productRepository, productFactory,
                productVersionUseCase, categoryRepository, brandRepository,
                snapshotConvertor, shopRepository, freightTemplateRepository,
                lastWriteMarker, tenantContextAccessor);
    }

    /* ================= fixtures ================= */

    /**
     * 商品 fixture（草稿态，双 SKU：黑/白；绑定模板 7001）。
     */
    private static Product productWithSkus() {
        return new Product(PRODUCT, SHOP_A, "经典款 T 恤", null, 11L, 21L,
                ProductStatus.DRAFT,
                new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑", "白")))),
                List.of(new Sku(3001L, PRODUCT, "hash-1", "颜色:黑", 8800L, true),
                        new Sku(3002L, PRODUCT, "hash-2", "颜色:白", null, false)));
    }

    /**
     * 商品 fixture（无规格模板/无 SKU/未绑定运费）。
     */
    private static Product productBare() {
        return new Product(PRODUCT, SHOP_A, "无模板商品", null, null, null,
                ProductStatus.DRAFT, null, List.of());
    }

    /* ================= happy path ================= */

    /**
     * happy-1 SKU 集回显：按模板展开序输出 SkuItem（price 分透传——
     * null 未定价保留、enabled 透传、specSummary 可读摘要）。
     */
    @Test
    @DisplayName("SKU 集回显：双 SKU 展开序 + 价格分透传 + 启用位")
    void listSkus_happy_twoSkus() {
        final Product product = productWithSkus();
        product.restoreFreightTemplateId(FREIGHT_TEMPLATE);
        when(productRepository.getByID(PRODUCT)).thenReturn(product);

        final List<SkuItem> skus = useCase.listSkus(PRODUCT);

        assertThat(skus).hasSize(2);
        final SkuItem first = skus.get(0);
        assertThat(first.id()).isEqualTo(3001L);
        assertThat(first.specHash()).isEqualTo("hash-1");
        assertThat(first.specSummary()).isEqualTo("颜色:黑");
        assertThat(first.price()).isEqualTo(8800L);
        assertThat(first.enabled()).isTrue();
        final SkuItem second = skus.get(1);
        assertThat(second.id()).isEqualTo(3002L);
        assertThat(second.price()).isNull();
        assertThat(second.enabled()).isFalse();
    }

    /**
     * happy-2 规格模板回显：维度按配置序输出 SpecDimensionRequest（名 +
     * 值列表）。
     */
    @Test
    @DisplayName("规格模板回显：双维度按配置序")
    void getSpecTemplate_happy_twoDimensions() {
        final Product product = new Product(PRODUCT, SHOP_A, "多规格商品", null, null, null,
                ProductStatus.DRAFT,
                new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑", "白")),
                        new SpecItem("尺码", List.of("M", "L")))),
                List.of());
        when(productRepository.getByID(PRODUCT)).thenReturn(product);

        final SpecTemplateRequest template = useCase.getSpecTemplate(PRODUCT);

        assertThat(template.dimensions()).hasSize(2);
        final SpecDimensionRequest color = template.dimensions().get(0);
        assertThat(color.name()).isEqualTo("颜色");
        assertThat(color.values()).containsExactly("黑", "白");
        final SpecDimensionRequest size = template.dimensions().get(1);
        assertThat(size.name()).isEqualTo("尺码");
        assertThat(size.values()).containsExactly("M", "L");
    }

    /**
     * happy-3 运费绑定回显：绑定模板 ID 投影（编辑页下拉初始值）。
     */
    @Test
    @DisplayName("运费绑定回显：已绑定模板 ID 投影")
    void getFreightTemplateBind_happy_bound() {
        final Product product = productBare();
        product.restoreFreightTemplateId(FREIGHT_TEMPLATE);
        when(productRepository.getByID(PRODUCT)).thenReturn(product);

        final FreightTemplateBindRequest bind = useCase.getFreightTemplateBind(PRODUCT);

        assertThat(bind.freightTemplateId()).isEqualTo(FREIGHT_TEMPLATE);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 规格模板回显：未配置模板 = 空维度列表的请求体形态
     * （非空设计：显式空模板而非 null，与写面空 dimensions 语义一致）。
     */
    @Test
    @DisplayName("规格模板回显：未配置 → 空维度列表（非 null）")
    void getSpecTemplate_notConfigured_emptyDimensions() {
        when(productRepository.getByID(PRODUCT)).thenReturn(productBare());

        final SpecTemplateRequest template = useCase.getSpecTemplate(PRODUCT);

        assertThat(template.dimensions()).isNotNull().isEmpty();
    }

    /**
     * critical-2 运费绑定回显：未绑定 = null（null 语义 = 解绑，与写面
     * 请求体一致）。
     */
    @Test
    @DisplayName("运费绑定回显：未绑定 → null")
    void getFreightTemplateBind_notBound_null() {
        when(productRepository.getByID(PRODUCT)).thenReturn(productBare());

        final FreightTemplateBindRequest bind = useCase.getFreightTemplateBind(PRODUCT);

        assertThat(bind.freightTemplateId()).isNull();
    }

    /**
     * critical-3 SKU 集回显：无规格模板无 SKU = 空列表（非 null）。
     */
    @Test
    @DisplayName("SKU 集回显：无模板 → 空列表（非 null）")
    void listSkus_noSkus_emptyList() {
        when(productRepository.getByID(PRODUCT)).thenReturn(productBare());

        final List<SkuItem> skus = useCase.listSkus(PRODUCT);

        assertThat(skus).isNotNull().isEmpty();
    }

    /* ================= fail path ================= */

    /**
     * fail-1 商品不存在/跨店铺（租户过滤 fail-closed）：SKU 集回显 404。
     */
    @Test
    @DisplayName("SKU 集回显：商品不存在 → 404（catalog.product_not_found）")
    void listSkus_productMissing_404() {
        when(productRepository.getByID(PRODUCT)).thenReturn(null);

        assertThatThrownBy(() -> useCase.listSkus(PRODUCT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), 404);
    }

    /**
     * fail-2 规格模板回显：商品不存在 → 404。
     */
    @Test
    @DisplayName("规格模板回显：商品不存在 → 404")
    void getSpecTemplate_productMissing_404() {
        when(productRepository.getByID(PRODUCT)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getSpecTemplate(PRODUCT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), 404);
    }

    /**
     * fail-3 运费绑定回显：商品不存在 → 404。
     */
    @Test
    @DisplayName("运费绑定回显：商品不存在 → 404")
    void getFreightTemplateBind_productMissing_404() {
        when(productRepository.getByID(PRODUCT)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getFreightTemplateBind(PRODUCT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), 404);
    }
}