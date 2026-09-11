package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.domain.catalog.factory.ProductEditVersionFactory;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.ProductEditVersionRepository;
import com.nona.domain.catalog.repo.ProductRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品编辑版本仓储集成测试：append-only 版本行落库（product_edit_version
 * 表，tenant=shopId 由写门禁注入）、按商品分页（新版本在前）与计数、
 * 同商品最大版本号（MAX+1 分配基础读）、行级删除拒绝（版本行只增不改）、
 * 商品删除级联清理版本行，与跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：版本行为商品聚合的 append-only 从表行（rootId 关联
 * product 主表，独立 Snowflake 主键，不装配进聚合内存——历史查询经本
 * 仓储按商品分页读取，领域模型决策）；(product_id, version_no) 唯一
 * 约束兜底并发冲突（DB 级防线，独立并发测试见
 * ProductEditVersionUniqueConstraintAcTest）。
 * <p>
 * 本文件断言机械装配（版本行插行/分页/租户过滤/级联）——
 * 为绿底座；版本链领域语义（保存留痕/版本号递增分配/回滚编排）
 * 由 ProductVersionUseCaseIntegrationAcTest / ProductRestoreContentUnitTest
 * 承载。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductEditVersionRepositoryIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9102";

    /**
     * 快照 JSON 样例
     */
    private static final String SNAPSHOT = "{\"name\":\"无线耳机\",\"images\":[],"
            + "\"attributes\":[],\"specTemplate\":null,\"skus\":[]}";

    /**
     * 版本仓储（被测对象）
     */
    @Autowired
    private ProductEditVersionRepository editVersionRepository;

    /**
     * 版本子表 JPA（断言行与清理）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 商品仓储（级联删除路径）
     */
    @Autowired
    private ProductRepository productRepository;

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
     * 商品聚合工厂（创建草稿）
     */
    @Autowired
    private ProductFactory productFactory;

    /**
     * 版本工厂（创建版本行）
     */
    @Autowired
    private ProductEditVersionFactory editVersionFactory;

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
     * 每用例前：提权清空五张表 + 建立请求作用域 + 商家 A 租户上下文。
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

    /**
     * happy：append 落库——版本行插入（tenant=A 由写门禁注入），字段
     * 全量读回（含审计产生时间），save 插行语义一致。
     */
    @Test
    @DisplayName("append落库后版本行字段完整读回")
    void append_insertsVersionRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final long productId = 81001L;

            final ProductEditVersion appended = tx.execute(status ->
                    editVersionRepository.append(editVersionFactory.createEdit(
                            productId, 1, SNAPSHOT, "72001")));

            assertThat(appended).isNotNull();
            assertThat(appended.getCreatedAt()).isNotNull();
            final ProductEditVersionPO row = editVersionJpaRepository.findById(appended.getId()).orElseThrow();
            assertThat(row.getTenantID()).isEqualTo(TENANT_A);
            assertThat(row.getProductId()).isEqualTo(productId);
            assertThat(row.getVersionNo()).isEqualTo(1);
            assertThat(row.getSnapshotJson()).isEqualTo(SNAPSHOT);
            assertThat(row.getOperator()).isEqualTo("72001");
            assertThat(row.getTriggerType()).isEqualTo(EditVersionTriggerType.EDIT);
    
        });
}

    /**
     * happy：按商品分页列出版本——新版本在前（version_no 倒序）、计数
     * 准确、maxVersionNo 返回当前最大值（无版本行返回 0）。
     */
    @Test
    @DisplayName("分页列表新版本在前且计数与最大版本号准确")
    void listByProductPaged_newestFirstWithCountAndMax() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final long productId = 81002L;
            for (int versionNo = 1; versionNo <= 3; versionNo++) {
                final int no = versionNo;
                tx.execute(status -> editVersionRepository.append(
                        editVersionFactory.createEdit(productId, no, SNAPSHOT, "72001")));
            }

            assertThat(editVersionRepository.maxVersionNo(productId)).isEqualTo(3);
            assertThat(editVersionRepository.maxVersionNo(99999L)).isZero();
            assertThat(editVersionRepository.countByProduct(productId)).isEqualTo(3);
            final List<ProductEditVersion> page =
                    editVersionRepository.listByProductPaged(productId, 0, 10);
            assertThat(page).extracting(ProductEditVersion::getVersionNo)
                    .containsExactly(3, 2, 1);
            assertThat(editVersionRepository.listByProductPaged(productId, 2, 2))
                    .extracting(ProductEditVersion::getVersionNo)
                    .containsExactly(1);
    
        });
}

    /**
     * happy：getByProductAndVersion 按商品 + 版本号取行（回滚素材读取）。
     */
    @Test
    @DisplayName("按商品与版本号取版本行")
    void getByProductAndVersion_locatesRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final long productId = 81003L;
            tx.executeWithoutResult(status -> {
                editVersionRepository.append(
                        editVersionFactory.createEdit(productId, 1, SNAPSHOT, "72001"));
                editVersionRepository.append(
                        editVersionFactory.createRollback(productId, 2, SNAPSHOT, "72001"));
            });

            final ProductEditVersion version =
                    editVersionRepository.getByProductAndVersion(productId, 2);
            assertThat(version).isNotNull();
            assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.ROLLBACK);
            assertThat(editVersionRepository.getByProductAndVersion(productId, 9)).isNull();
    
        });
}

    /**
     * critical：save 为插行语义（append-only，无更新路径——重复 save 同一
     * 实体会导致唯一约束冲突，行级删除被显式拒绝）。
     */
    @Test
    @DisplayName("行级删除拒绝且save为插行语义")
    void rowDelete_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final ProductEditVersion version = editVersionFactory.createEdit(
                    81004L, 1, SNAPSHOT, "72001");

            assertThatThrownBy(() -> editVersionRepository.delete(version))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> editVersionRepository.deleteByID(1L))
                    .isInstanceOf(UnsupportedOperationException.class);
            tx.execute(status -> editVersionRepository.save(version));
            assertThat(editVersionJpaRepository.countByProductId(81004L)).isEqualTo(1);
    
        });
}

    /**
     * error：删除商品级联清理版本行（deleteByID 真实删除语义：删四个
     * 从表 + 主表，返回真实条数）。
     */
    @Test
    @DisplayName("删除商品级联清理版本行")
    void deleteProduct_cascadesVersionRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = tx.execute(status -> {
                final Product created = productFactory.createDraft(9101L, "待删商品", null, null, null);
                productRepository.save(created);
                editVersionRepository.append(editVersionFactory.createEdit(
                        created.getId(), 1, SNAPSHOT, "72001"));
                editVersionRepository.append(editVersionFactory.createEdit(
                        created.getId(), 2, SNAPSHOT, "72001"));
                return created;
            });

            final int deleted = tx.execute(status -> productRepository.deleteByID(product.getId()));

            assertThat(deleted).isEqualTo(1);
            assertThat(editVersionJpaRepository.countByProductId(product.getId())).isZero();
            assertThat(productJpaRepository.existsById(product.getId())).isFalse();
    
        });
}

    /**
     * error：跨店铺版本行访问按不存在呈现——fail-closed（租户过滤拦截，
     * 不泄露归属）。
     */
    @Test
    @DisplayName("跨店铺版本访问按不存在呈现")
    void crossTenantVersionAccess_failClosed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final long productId = 81005L;
            tx.execute(status -> editVersionRepository.append(
                    editVersionFactory.createEdit(productId, 1, SNAPSHOT, "72001")));

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                assertThat(editVersionRepository.getByProductAndVersion(productId, 1)).isNull();
                assertThat(editVersionRepository.listByProductPaged(productId, 0, 10)).isEmpty();
                assertThat(editVersionRepository.countByProduct(productId)).isZero();
                assertThat(editVersionRepository.maxVersionNo(productId)).isZero();
    
            });
        });
}
}