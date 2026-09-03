package com.nona.inf.persistence.repository;

import com.nona.inf.context.ThreadContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.ProductAttributePO;
import com.nona.inf.persistence.po.catalog.SkuPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品聚合从表唯一约束兜底契约测试（DB 级并发防线，聚合守卫外的第二道
 * 保险）：product_sku 表 (product_id, spec_hash) 唯一、product_attribute
 * 表 (product_id, attr_key) 唯一——绕过聚合守卫直插重复行（模拟并发
 * 同时落库）被唯一约束拒绝。
 * <p>
 * 聚合守卫负责正常路径查重；唯一约束兜底并发/直插路径——两条防线共同
 * 保证「同商品内规格组合唯一」「同商品内属性键唯一」在数据库层面恒成立。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class SkuUniqueConstraintRepositoryTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9301";

    /**
     * SKU 子表 JPA（直插验证唯一约束）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

    /**
     * 属性子表 JPA（直插验证唯一约束）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 请求级上下文（模拟商家请求租户=当前店铺）
     */
    @Autowired
    private ThreadContext threadContext;

    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两张从表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        threadContext.setTenantID(TENANT_A);
    }

    /**
     * 每用例后：清理请求作用域与租户上下文，避免跨用例污染。
     */
    @AfterEach
    void tearDown() {
        threadContext.setTenantID(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * critical：product_sku 唯一约束兜底——同商品同 spec_hash 的第二行
     * 直插被拒绝（并发重复落库的 DB 级防线）；首行插入成功。
     */
    @Test
    @DisplayName("重复 spec_hash 直插被唯一约束拒绝")
    void duplicateSpecHash_rejectedByUniqueConstraint() {
        skuJpaRepository.save(newSkuPo(81001L, 91001L, "hash-a", "颜色:黑", 1999L, true));

        assertThatThrownBy(() ->
                skuJpaRepository.save(newSkuPo(81002L, 91001L, "hash-a", "颜色:黑", null, false)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(skuJpaRepository.findById(81001L)).isPresent();
        assertThat(skuJpaRepository.findById(81002L)).isEmpty();
    }

    /**
     * critical：不同商品的相同 spec_hash 互不冲突（唯一约束按商品维度，
     * 商品间规格组合可相同）。
     */
    @Test
    @DisplayName("不同商品的相同 spec_hash 合法共存")
    void sameSpecHashAcrossProducts_allowed() {
        skuJpaRepository.save(newSkuPo(81011L, 91001L, "hash-a", "颜色:黑", 1999L, true));
        skuJpaRepository.save(newSkuPo(81012L, 91002L, "hash-a", "颜色:黑", 2599L, false));

        assertThat(skuJpaRepository.count()).isEqualTo(2);
        assertThat(skuJpaRepository.findById(81011L)).isPresent();
        assertThat(skuJpaRepository.findById(81012L)).isPresent();
    }

    /**
     * critical：product_attribute 唯一约束兜底——同商品同 attr_key 的
     * 第二行直插被拒绝（并发重复落库的 DB 级防线）。
     */
    @Test
    @DisplayName("重复属性键直插被唯一约束拒绝")
    void duplicateAttributeKey_rejectedByUniqueConstraint() {
        attributeJpaRepository.save(newAttributePo(82001L, 91001L, "材质", "纯棉"));

        assertThatThrownBy(() ->
                attributeJpaRepository.save(newAttributePo(82002L, 91001L, "材质", "涤纶")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attributeJpaRepository.findById(82001L)).isPresent();
        assertThat(attributeJpaRepository.findById(82002L)).isEmpty();
    }

    /**
     * 构造 SKU 行（直插绕过聚合守卫，验证 DB 兜底）。
     *
     * @param id         SKU ID
     * @param productId  归属商品 ID
     * @param specHash   规格摘要
     * @param specSummary 可读摘要
     * @param price      价格（可空）
     * @param enabled    是否启用
     * @return SKU 行
     */
    private static SkuPO newSkuPo(long id, long productId, String specHash, String specSummary,
                                  Long price, boolean enabled) {
        final SkuPO po = new SkuPO();
        po.setId(id);
        po.setProductId(productId);
        po.setSpecHash(specHash);
        po.setSpecSummary(specSummary);
        po.setPrice(price);
        po.setEnabled(enabled);
        return po;
    }

    /**
     * 构造属性行（直插绕过聚合守卫，验证 DB 兜底）。
     *
     * @param id        属性 ID
     * @param productId 归属商品 ID
     * @param key       属性键
     * @param value     属性值（可空）
     * @return 属性行
     */
    private static ProductAttributePO newAttributePo(long id, long productId, String key, String value) {
        final ProductAttributePO po = new ProductAttributePO();
        po.setId(id);
        po.setProductId(productId);
        po.setAttrKey(key);
        po.setAttrValue(value);
        return po;
    }
}