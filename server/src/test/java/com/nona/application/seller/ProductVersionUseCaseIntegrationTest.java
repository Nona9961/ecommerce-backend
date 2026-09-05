package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.ProductVersionItem;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.util.JacksonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品编辑版本用例集成测试：保存留痕（每次编辑落库生成版本行）、版本
 * 历史查询（按商品分页，新版本在前）与回滚（内容重置 + ROLLBACK 新版本
 * 行）闭环，覆盖操作人记录、空聚合快照、快照一致性读回与跨店铺
 * fail-closed。
 * <p>
 * 契约语义：创建即基线版本（version_no=1）；每次变更面保存后追加一行
 * EDIT；回滚 = 以历史快照为新版本内容重走保存流程（trigger_type=ROLLBACK，
 * 版本号递增）；版本行 append-only 只增不改；删商品级联删版本行；
 * 版本行与商品同租户（tenant=shopId），跨店铺按不存在呈现。
 * <p>
 * 红状态说明：recordEdit / history / rollback 为契约声明（方法体抛
 * UnsupportedOperationException）——依赖这些行为的用例全部红，红因 =
 * 实现缺失；装配底座（版本行插行/分页/租户过滤/工厂）已实现为绿底座。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductVersionUseCaseIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9102";

    /**
     * 商家 A 账号 ID（操作人断言用）
     */
    private static final String OPERATOR_A = "72001";

    /**
     * 商品版本用例（被测对象）
     */
    @Autowired
    private ProductVersionUseCase productVersionUseCase;

    /**
     * 商品仓储（保存路径与跨店隔离断言）
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * 商品聚合工厂
     */
    @Autowired
    private ProductFactory productFactory;

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
     * 编程式事务模板（模拟用例层事务边界）
     */
    @Autowired
    private TransactionTemplate tx;


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
     * happy：保存留痕 + 历史查询——首次保存生成基线版本（version_no=1、
     * 触发 EDIT、操作人=认证身份），再次编辑保存生成版本 2；历史分页
     * 新版本在前，条目含版本号/触发类型/操作人/产生时间/内容摘要。
     */
    @Test
    @DisplayName("保存留痕后历史查询新版本在前")
    void recordEdit_thenHistory_pagedNewestFirst() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("无线耳机");
            productVersionUseCase.recordEdit(product);
            final Product loaded = productRepository.getByID(product.getId());
            loaded.updateInfo("无线耳机Pro", "升级款", null, null);
            productRepository.save(loaded);
            productVersionUseCase.recordEdit(loaded);

            final PageResult<ProductVersionItem> page =
                    productVersionUseCase.history(product.getId(), new PageQuery(1, 10));

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.records()).extracting(ProductVersionItem::versionNo)
                    .containsExactly(2, 1);
            assertThat(page.records().get(0).triggerType()).isEqualTo("EDIT");
            assertThat(page.records().get(0).operator()).isEqualTo(OPERATOR_A);
            assertThat(page.records().get(0).createdAt()).isNotBlank();
            assertThat(page.records().get(0).summary()).contains("无线耳机Pro");
    
        });
}

    /**
     * happy：回滚生成新版本——回滚到版本 1，聚合内容重置为版本 1 内容，
     * 版本链追加 ROLLBACK 行（version_no 递增，内容=目标版本内容）。
     */
    @Test
    @DisplayName("回滚生成ROLLBACK新版本且内容重置")
    void rollback_restoresContentAndAppendsVersion() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("初版");
            productVersionUseCase.recordEdit(product);
            final Product edited = productRepository.getByID(product.getId());
            edited.updateInfo("改后名", "改后描述", null, null);
            productRepository.save(edited);
            productVersionUseCase.recordEdit(edited);

            final var detail = productVersionUseCase.rollback(product.getId(), 1);

            assertThat(detail.name()).isEqualTo("初版");
            final ProductEditVersionPO rollbackRow = editVersionJpaRepository
                    .findByProductIdAndVersionNo(product.getId(), 3).orElseThrow();
            assertThat(rollbackRow.getTriggerType().name()).isEqualTo("ROLLBACK");
            assertThat(rollbackRow.getSnapshotJson()).contains("\"初版\"");
    
        });
}

    // ---- Critical path ----

    /**
     * critical：连续多次保存版本号连续递增（1,2,3 严格 +1）。
     */
    @Test
    @DisplayName("连续保存版本号连续递增")
    void consecutiveSaves_versionNoStrictlyIncreasing() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("版本一");
            productVersionUseCase.recordEdit(product);
            for (int round = 2; round <= 3; round++) {
                final Product loaded = productRepository.getByID(product.getId());
                loaded.updateInfo("版本" + round, null, null, null);
                productRepository.save(loaded);
                productVersionUseCase.recordEdit(loaded);
            }

            assertThat(productVersionUseCase.history(product.getId(), new PageQuery(1, 10)).records())
                    .extracting(ProductVersionItem::versionNo)
                    .containsExactly(3, 2, 1);
    
        });
}

    /**
     * critical：回滚后再次保存——新保存基于回滚内容生成后续版本（版本号
     * 仍连续递增，内容 = 回滚后内容）。
     */
    @Test
    @DisplayName("回滚后再次保存基于回滚内容生成新版本")
    void saveAfterRollback_basedOnRollbackContent() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("初版");
            productVersionUseCase.recordEdit(product);
            final Product edited = productRepository.getByID(product.getId());
            edited.updateInfo("改后名", null, null, null);
            productRepository.save(edited);
            productVersionUseCase.recordEdit(edited);

            productVersionUseCase.rollback(product.getId(), 1);
            final Product reloaded = productRepository.getByID(product.getId());
            reloaded.updateInfo("回滚后微调", null, null, null);
            productRepository.save(reloaded);
            productVersionUseCase.recordEdit(reloaded);

            final PageResult<ProductVersionItem> page =
                    productVersionUseCase.history(product.getId(), new PageQuery(1, 10));
            assertThat(page.records()).extracting(ProductVersionItem::versionNo)
                    .containsExactly(4, 3, 2, 1);
            assertThat(page.records().get(0).triggerType()).isEqualTo("EDIT");
            assertThat(page.records().get(0).summary()).contains("回滚后微调");
    
        });
}

    /**
     * critical：空聚合（草稿只有名称）快照——快照内容与聚合当前内容一致
     * （无图片/属性/SKU、无模板），留痕行可读回验证。
     */
    @Test
    @DisplayName("空聚合快照内容与聚合一致且读回验证")
    void emptyDraft_snapshotMatchesAggregate() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("只有名字");
            productVersionUseCase.recordEdit(product);

            final ProductEditVersionPO row = editVersionJpaRepository
                    .findByProductIdAndVersionNo(product.getId(), 1).orElseThrow();
            final ObjectNode snapshot = JacksonUtil.jsonToObjNode(row.getSnapshotJson());
            assertThat(snapshot.path("name").asText()).isEqualTo("只有名字");
            assertThat(snapshot.path("images").size()).isZero();
            assertThat(snapshot.path("attributes").size()).isZero();
            assertThat(snapshot.path("skus").size()).isZero();
            assertThat(snapshot.path("specTemplate").isNull()).isTrue();
    
        });
}

    // ---- Error path ----

    /**
     * error：回滚不存在版本号按不存在呈现（404 语义业务码）。
     */
    @Test
    @DisplayName("回滚不存在版本号拒绝")
    void rollback_missingVersionNotFound() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("商品");
            productVersionUseCase.recordEdit(product);

            assertThatThrownBy(() -> productVersionUseCase.rollback(product.getId(), 999))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_NOT_FOUND.code()));
    
        });
}

    /**
     * error：非法版本号（非正数）拒绝（400 语义业务码）。
     */
    @Test
    @DisplayName("非法版本号回滚拒绝")
    void rollback_invalidVersionNoRejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("商品");
            productVersionUseCase.recordEdit(product);

            assertThatThrownBy(() -> productVersionUseCase.rollback(product.getId(), 0))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code()));
    
        });
}

    /**
     * error：跨店铺访问版本（历史/回滚）按不存在呈现——fail-closed，
     * 不泄露归属。
     */
    @Test
    @DisplayName("跨店铺版本访问按不存在呈现")
    void crossShopVersionAccess_failClosed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR_A);
            final Product product = saveProduct("A店商品");
            productVersionUseCase.recordEdit(product);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                TrackingContext.scope().setIdentity(OPERATOR_A);
                assertThatThrownBy(() -> productVersionUseCase.rollback(product.getId(), 1))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code()));
                assertThat(productVersionUseCase.history(product.getId(), new PageQuery(1, 10)).total())
                        .isZero();
    
            });
        });
}

    /**
     * 通过仓储保存商品草稿（事务内，模拟用例写路径落库）。
     *
     * @param name 商品名称
     * @return 已持久化商品
     */
    private Product saveProduct(String name) {
        return tx.execute(status -> {
            final Product created = productFactory.createDraft(9101L, name, null, null, null);
            productRepository.save(created);
            return created;
        });
    }
}