package com.nona.application.seller;

import com.nona.api.seller.ProductAttributeItem;
import com.nona.api.seller.ProductAttributeRequest;
import com.nona.api.seller.ProductDetail;
import com.nona.api.seller.ProductDraftRequest;
import com.nona.api.seller.ProductImageItem;
import com.nona.api.seller.ProductImageRequest;
import com.nona.api.seller.SkuEnabledRequest;
import com.nona.api.seller.SkuItem;
import com.nona.api.seller.SkuPriceRequest;
import com.nona.api.seller.SpecDimensionRequest;
import com.nona.api.seller.SpecTemplateRequest;
import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品写路径留痕接线集成测试：商品各变更面用例（ProductUseCase）保存
 * 成功后同事务 recordEdit——创建即基线版本（version_no=1）、每次实际
 * 落库的保存追加 EDIT 版本行、无变更保存（变更集为空）不插行、删除
 * 商品级联清理版本行。
 * <p>
 * 契约语义（创建基线与每次修改留痕都由写路径用例自动
 * 触发（不经调用方手调 recordEdit）；「变更集为空不插行」由写路径
 * 仅在仓储 save 实际落库（返回 true）时调 recordEdit 保证——与
 * ProductVersionUseCaseIntegrationAcTest 的显式 recordEdit 编排互补，
 * 本测试验证接线本身。
 * <p>
 * 操作人经认证上下文注入（测试模拟商家请求身份），版本行与商品同
 * 租户（tenant=shopId）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductUseCaseVersionTraceIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final long SHOP_A = 9101L;

    /**
     * 商家 A 账号 ID（操作人断言用）
     */
    private static final String OPERATOR_A = "72001";

    /**
     * 商品写路径用例（被测对象：留痕接线主体）
     */
    @Autowired
    private ProductUseCase productUseCase;

    /**
     * 版本子表 JPA（版本行断言与清理）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 商品主表 JPA（清理）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 图片子表 JPA（清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * 属性子表 JPA（清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * SKU 子表 JPA（清理）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空五张表 + 建立请求作用域 + 商家 A 租户与身份上下文。
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
    }

    // ---- Happy path ----

    /**
     * happy：创建商品即写基线版本——version_no=1、触发 EDIT、操作人=
     * 认证身份、快照含当前名称（创建留痕自动接线，不经调用方手调）。
     */
    @Test
    @DisplayName("创建商品自动写基线版本")
    void createDraft_writesBaselineVersion() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final ProductDetail detail =
                    productUseCase.createDraft(SHOP_A, new ProductDraftRequest("基线商品", null, null, null));

            final ProductEditVersionPO row = editVersionJpaRepository
                    .findByProductIdAndVersionNo(detail.id(), 1).orElseThrow();
            assertThat(row.getTriggerType()).isEqualTo(EditVersionTriggerType.EDIT);
            assertThat(row.getOperator()).isEqualTo(OPERATOR_A);
            assertThat(row.getSnapshotJson()).contains("基线商品");
            assertThat(editVersionJpaRepository.countByProductId(detail.id())).isEqualTo(1);
    
        });
}

    /**
     * critical：全部写面逐次留痕——update/图片增删与主图转移/属性增删改/
     * 规格模板重建/改价/启停每步实际落库后版本号严格 +1，最新快照跟随
     * 当前内容。
     */
    @Test
    @DisplayName("全部写面逐次留痕版本号递增")
    void everyWritePath_tracesEachSave() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final ProductDetail draft =
                    productUseCase.createDraft(SHOP_A, new ProductDraftRequest("留痕商品", null, null, null));
            final long productId = draft.id();
            assertVersionNo(productId, 1);

            productUseCase.update(productId, new ProductDraftRequest("留痕商品2", "升级", null, null));
            assertVersionNo(productId, 2);

            final ProductImageItem imageA = productUseCase.addImage(productId,
                    new ProductImageRequest("/files/a.png", true));
            assertVersionNo(productId, 3);
            final ProductImageItem imageB = productUseCase.addImage(productId,
                    new ProductImageRequest("/files/b.png", false));
            assertVersionNo(productId, 4);
            productUseCase.setPrimaryImage(productId, imageB.id());
            assertVersionNo(productId, 5);
            productUseCase.removeImage(productId, imageB.id());
            assertVersionNo(productId, 6);

            final ProductAttributeItem attribute = productUseCase.addAttribute(productId,
                    new ProductAttributeRequest("材质", "纯棉"));
            assertVersionNo(productId, 7);
            productUseCase.updateAttribute(productId, attribute.id(),
                    new ProductAttributeRequest("材质", "棉"));
            assertVersionNo(productId, 8);
            assertThat(editVersionJpaRepository.findByProductIdAndVersionNo(productId, 8).orElseThrow()
                    .getSnapshotJson()).contains("\"材质\"");
            productUseCase.removeAttribute(productId, attribute.id());
            assertVersionNo(productId, 9);

            final List<SkuItem> skus = productUseCase.configureSpecTemplate(productId,
                    new SpecTemplateRequest(List.of(new SpecDimensionRequest("颜色", List.of("黑", "白")))));
            assertVersionNo(productId, 10);
            final SkuItem black = skus.get(0);
            productUseCase.updateSkuPrice(productId, black.id(), new SkuPriceRequest(1999L));
            assertVersionNo(productId, 11);
            productUseCase.setSkuEnabled(productId, black.id(), new SkuEnabledRequest(true));
            assertVersionNo(productId, 12);

            final ProductEditVersionPO latest = editVersionJpaRepository
                    .findByProductIdAndVersionNo(productId, 12).orElseThrow();
            assertThat(latest.getTriggerType()).isEqualTo(EditVersionTriggerType.EDIT);
            assertThat(latest.getSnapshotJson()).contains("留痕商品2");
            assertThat(latest.getSnapshotJson()).contains("1999");
    
        });
}

    /**
     * critical：无实际变更的保存不插版本行——相同内容再次 update（变更集
     * 为空，仓储 save 无落库）版本数不变（「每次实际修改留痕」语义：
     * 无修改不留痕）。
     */
    @Test
    @DisplayName("无变更保存不插版本行")
    void unchangedSave_skipsVersionRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final ProductDetail draft =
                    productUseCase.createDraft(SHOP_A, new ProductDraftRequest("不变商品", null, null, null));
            final long productId = draft.id();

            productUseCase.update(productId, new ProductDraftRequest("不变商品", null, null, null));

            assertThat(editVersionJpaRepository.countByProductId(productId)).isEqualTo(1);
            assertThat(editVersionJpaRepository.maxVersionNo(productId)).isEqualTo(1);
    
        });
}

    /**
     * error：删除商品级联清理版本行（写路径 deleteByID 的真实删除语义
     * 延伸——版本链随商品生命周期整体消亡）。
     */
    @Test
    @DisplayName("删除商品级联清理版本行")
    void deleteProduct_cascadesVersionRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final ProductDetail draft =
                    productUseCase.createDraft(SHOP_A, new ProductDraftRequest("待删商品", null, null, null));
            final long productId = draft.id();
            productUseCase.update(productId, new ProductDraftRequest("待删商品2", null, null, null));
            assertThat(editVersionJpaRepository.countByProductId(productId)).isEqualTo(2);

            productUseCase.delete(productId);

            assertThat(editVersionJpaRepository.countByProductId(productId)).isZero();
            assertThat(productJpaRepository.existsById(productId)).isFalse();
    
        });
}

    /**
     * 断言同商品当前最新版本号（版本链递增进度）。
     *
     * @param productId 商品 ID
     * @param expected  期望版本号
     */
    private void assertVersionNo(long productId, int expected) {
        assertThat(editVersionJpaRepository.maxVersionNo(productId)).isEqualTo(expected);
    }
}