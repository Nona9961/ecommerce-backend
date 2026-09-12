package com.nona.inf.persistence.repository;

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
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品编辑版本唯一约束兜底契约测试（DB 级并发防线）：product_edit_version
 * 表 (product_id, version_no) 唯一——并发同商品编辑时 MAX+1 分配可能取得
 * 同一版本号，重复插入被唯一约束拒绝（版本链恒等式「同商品版本号唯一」
 * 的数据库层保证；应用层分配正常路径 + DB 兜底并发路径双保险）。
 * <p>
 * 与 SKU/属性唯一约束兜底（SkuUniqueConstraintRepositoryAcTest）同形态：
 * 绕过分配路径直插重复行（模拟并发同时落库）被唯一约束拒绝，首个写入
 * 行保持；不同商品的相同版本号互不冲突（唯一性按商品维度）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductEditVersionUniqueConstraintAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9301";

    /**
     * 版本子表 JPA（直插验证唯一约束与清理）
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
     * critical：同商品同版本号的第二行直插被唯一约束拒绝（并发 MAX+1
     * 冲突的 DB 级防线）；首行保持、冲突行不落库。
     */
    @Test
    @DisplayName("重复版本号直插被唯一约束拒绝")
    void duplicateVersionNo_rejectedByUniqueConstraint() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            editVersionJpaRepository.save(newVersionPo(81001L, 91001L, 1, "v1"));

            assertThatThrownBy(() ->
                    editVersionJpaRepository.save(newVersionPo(81002L, 91001L, 1, "v2")))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(editVersionJpaRepository.findById(81001L)).isPresent();
            assertThat(editVersionJpaRepository.findById(81002L)).isEmpty();
    
        });
}

    /**
     * critical：不同商品的相同版本号互不冲突（唯一约束按商品维度，各
     * 商品版本链独立编号）。
     */
    @Test
    @DisplayName("不同商品的相同版本号合法共存")
    void sameVersionNoAcrossProducts_allowed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            editVersionJpaRepository.save(newVersionPo(81011L, 91001L, 1, "v1"));
            editVersionJpaRepository.save(newVersionPo(81012L, 91002L, 1, "v1"));

            assertThat(editVersionJpaRepository.findById(81011L)).isPresent();
            assertThat(editVersionJpaRepository.findById(81012L)).isPresent();
    
        });
}

    /**
     * 构造版本行（直插绕过分配路径，验证 DB 兜底）。
     *
     * @param id        行 ID
     * @param productId 归属商品 ID
     * @param versionNo 版本号
     * @param snapshot  快照 JSON
     * @return 版本行
     */
    private static ProductEditVersionPO newVersionPo(long id, long productId,
                                                     int versionNo, String snapshot) {
        final ProductEditVersionPO po = new ProductEditVersionPO();
        po.setId(id);
        po.setProductId(productId);
        po.setVersionNo(versionNo);
        po.setSnapshotJson(snapshot);
        po.setOperator("72001");
        po.setTriggerType(EditVersionTriggerType.EDIT);
        return po;
    }
}