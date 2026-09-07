package com.nona.domain.catalog.service;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 商品查询门面运费回退契约测试（非空设计 §2.5 查询侧落点）：买家详情
 * 读 freight 恒非空——商品未绑定模板 → 回退店铺默认模板概要；绑定 → 绑定
 * 模板概要；绑定悬挂（模板行缺失，脏数据）→ 宽容回退默认概要（详情不
 * 404）；默认模板被编辑后回退值同步（新规则）；默认模板缺失（开店必建
 * 故不可达的系统损坏）→ 防御拒绝（catalog.freight_default_template_not_found
 * 500，不静默降级为 null/包邮）。
 * <p>
 * 红阶段：getBuyerView 为签名冻结（实现缺失）——全部用例红于
 * UnsupportedOperationException，绿阶段实现后转为断言期验证。
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryFacadeFreightFallbackTest {

    /**
     * 商品 ID（在售）
     */
    private static final long PRODUCT_ID = 2001L;

    /**
     * 商品归属店铺 ID（回退锚点定位依据）
     */
    private static final long SHOP_ID = 4001L;

    /**
     * 店铺默认模板 ID
     */
    private static final long DEFAULT_ID = 6001L;

    /**
     * 商品显式绑定模板 ID
     */
    private static final long BOUND_ID = 6002L;

    /**
     * 悬挂绑定模板 ID（绑定了但模板行缺失——脏数据形态）
     */
    private static final long DANGLING_ID = 6999L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private InventoryFacade inventoryFacade;

    @Mock
    private Product product;

    private ProductQueryFacade queryFacade;

    /**
     * 装配门面（mock 依赖）与商品桩：在售商品、无图/无属性/无 SKU/
     * 无规格模板的读面最小形态；店铺卡片桩。
     */
    @BeforeEach
    void setUp() {
        queryFacade = new ProductQueryFacadeImpl(
                productRepository, freightTemplateRepository, shopRepository, inventoryFacade);
        when(productRepository.getByID(PRODUCT_ID)).thenReturn(product);
        when(product.getId()).thenReturn(PRODUCT_ID);
        when(product.getShopId()).thenReturn(SHOP_ID);
        when(product.getName()).thenReturn("测试商品");
        when(product.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(product.imagesOrdered()).thenReturn(List.of());
        when(product.attributesOrdered()).thenReturn(List.of());
        when(product.skusOrdered()).thenReturn(List.of());
        when(product.getSpecTemplate()).thenReturn(Optional.empty());
    }

    /**
     * happy：商品未绑定运费模板 → freight 恒非空，内容 = 店铺默认模板
     * 概要（回退锚点生效，null 不做语义载体）。
     */
    @Test
    @DisplayName("未绑定模板→回退店铺默认模板概要")
    void unboundProduct_freightFallsBackToDefaultTemplate() {
        when(product.getFreightTemplateId()).thenReturn(null);
        final FreightTemplate defaultTemplate = new FreightTemplate(DEFAULT_ID, SHOP_ID,
                "默认运费模板", FreightRuleType.FREE, null, null, null,
                FreightTemplateStatus.ENABLED);
        when(freightTemplateRepository.findDefaultByShopId(SHOP_ID)).thenReturn(defaultTemplate);

        final ProductBuyerView view = queryFacade.getBuyerView(PRODUCT_ID);

        assertThat(view.freight()).isNotNull();
        assertThat(view.freight().templateId()).isEqualTo(DEFAULT_ID);
        assertThat(view.freight().name()).isEqualTo("默认运费模板");
        assertThat(view.freight().ruleType()).isEqualTo("FREE");
        assertThat(view.freight().perItemPrice()).isNull();
    }

    /**
     * happy（回归锚点）：商品绑定模板 → freight = 绑定模板概要（绑定
     * 语义优先于回退）。
     */
    @Test
    @DisplayName("绑定模板→绑定模板概要")
    void boundProduct_freightIsBoundTemplate() {
        when(product.getFreightTemplateId()).thenReturn(BOUND_ID);
        final FreightTemplate bound = new FreightTemplate(BOUND_ID, SHOP_ID,
                "按件模板", FreightRuleType.PER_ITEM, 800L, null, null,
                FreightTemplateStatus.ENABLED);
        when(freightTemplateRepository.getByID(BOUND_ID)).thenReturn(bound);

        final ProductBuyerView view = queryFacade.getBuyerView(PRODUCT_ID);

        assertThat(view.freight()).isNotNull();
        assertThat(view.freight().templateId()).isEqualTo(BOUND_ID);
        assertThat(view.freight().name()).isEqualTo("按件模板");
        assertThat(view.freight().ruleType()).isEqualTo("PER_ITEM");
        assertThat(view.freight().perItemPrice()).isEqualTo(800L);
    }

    /**
     * critical：绑定悬挂（商品绑定的模板行缺失——配置脏数据）→ 宽容回退
     * 默认模板概要，详情读不 404（两态语义显式区分：悬挂回退 vs 缺失拒绝）。
     */
    @Test
    @DisplayName("绑定悬挂→宽容回退默认模板概要")
    void danglingBinding_freightFallsBackToDefaultTemplate() {
        when(product.getFreightTemplateId()).thenReturn(DANGLING_ID);
        when(freightTemplateRepository.getByID(DANGLING_ID)).thenReturn(null);
        final FreightTemplate defaultTemplate = new FreightTemplate(DEFAULT_ID, SHOP_ID,
                "默认运费模板", FreightRuleType.FREE, null, null, null,
                FreightTemplateStatus.ENABLED);
        when(freightTemplateRepository.findDefaultByShopId(SHOP_ID)).thenReturn(defaultTemplate);

        final ProductBuyerView view = queryFacade.getBuyerView(PRODUCT_ID);

        assertThat(view.freight()).isNotNull();
        assertThat(view.freight().templateId()).isEqualTo(DEFAULT_ID);
    }

    /**
     * critical：店铺默认模板被编辑（规则改为满额免邮）后，未绑定商品的
     * 回退值同步反映新规则（回退读的是默认模板当前规则，非开店时快照）。
     */
    @Test
    @DisplayName("默认模板编辑后回退值同步")
    void editedDefault_freightReflectsNewRules() {
        when(product.getFreightTemplateId()).thenReturn(null);
        final FreightTemplate editedDefault = new FreightTemplate(DEFAULT_ID, SHOP_ID,
                "满99包邮", FreightRuleType.THRESHOLD_FREE, null, 1000L, 9900L,
                FreightTemplateStatus.ENABLED);
        when(freightTemplateRepository.findDefaultByShopId(SHOP_ID)).thenReturn(editedDefault);

        final ProductBuyerView view = queryFacade.getBuyerView(PRODUCT_ID);

        assertThat(view.freight()).isNotNull();
        assertThat(view.freight().templateId()).isEqualTo(DEFAULT_ID);
        assertThat(view.freight().ruleType()).isEqualTo("THRESHOLD_FREE");
        assertThat(view.freight().baseFreight()).isEqualTo(1000L);
        assertThat(view.freight().freeThreshold()).isEqualTo(9900L);
    }

    /**
     * fail：店铺默认模板缺失（开店必建故正常不可达——系统数据损坏面）→
     * 防御拒绝 catalog.freight_default_template_not_found（500），不静默
     * 降级为 null/包邮（null 不做语义载体）。
     */
    @Test
    @DisplayName("默认模板缺失→防御拒绝")
    void defaultTemplateMissing_defensiveReject() {
        when(product.getFreightTemplateId()).thenReturn(null);
        when(freightTemplateRepository.findDefaultByShopId(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> queryFacade.getBuyerView(PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("catalog.freight_default_template_not_found");
    }
}