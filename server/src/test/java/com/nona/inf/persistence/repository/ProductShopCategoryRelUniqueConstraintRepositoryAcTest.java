package com.nona.inf.persistence.repository;

import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.ProductShopCategoryRelPO;
import com.nona.inf.persistence.repository.jpa.ProductShopCategoryRelJpaRepository;
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
 * 商品-店铺分类绑定从表唯一约束兜底契约测试（DB 级并发防线，聚合守卫
 * 外的第二道保险，与 {@code SkuUniqueConstraintRepositoryAcTest} 同形制）：
 * product_shop_category_rel 表 (product_id, shop_category_id) 唯一——绕过
 * 聚合守卫直插重复绑定行（模拟并发同时落库）被唯一约束拒绝。
 * <p>
 * 聚合守卫负责正常路径去重；唯一约束兜底并发/直插路径——两条防线共同
 * 保证「同一商品与同一店铺分类的绑定关系在数据库层面至多一行」恒成立。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductShopCategoryRelUniqueConstraintRepositoryAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9301";

    /**
     * rel 从表 JPA（直插验证唯一约束）
     */
    @Autowired
    private ProductShopCategoryRelJpaRepository relJpaRepository;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空从表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> relJpaRepository.deleteAll());
    }

    /**
     * critical：唯一约束兜底——同一商品绑定同一店铺分类的第二行直插被
     * 拒绝（并发重复落库的 DB 级防线）；首行插入成功。
     */
    @Test
    @DisplayName("重复 (product_id, shop_category_id) 直插被唯一约束拒绝")
    void duplicateBind_rejectedByUniqueConstraint() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            relJpaRepository.save(newRelPo(82001L, 91001L, 92001L));

            assertThatThrownBy(() ->
                    relJpaRepository.save(newRelPo(82002L, 91001L, 92001L)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(relJpaRepository.findById(82001L)).isPresent();
            assertThat(relJpaRepository.findById(82002L)).isEmpty();
    
        });
}

    /**
     * critical：不同商品的同品类绑定互不冲突；同商品绑不同分类合法共存
     * （唯一约束按 (product_id, shop_category_id) 组合维度，多对多两侧
     * 均允许一对多）。
     */
    @Test
    @DisplayName("不同商品绑同分类/同商品绑不同分类均合法")
    void distinctCombinations_allowed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            relJpaRepository.save(newRelPo(82011L, 91001L, 92001L));
            relJpaRepository.save(newRelPo(82012L, 91002L, 92001L));
            relJpaRepository.save(newRelPo(82013L, 91001L, 92002L));

            assertThat(relJpaRepository.count()).isEqualTo(3);
    
        });
}

    /**
     * 构造绑定行 PO（行主键/归属商品/绑定分类/租户齐备）。
     *
     * @param id             行主键（Snowflake ID 形态）
     * @param productId      归属商品 ID
     * @param shopCategoryId 店铺分类 ID
     * @return 绑定行 PO
     */
    private ProductShopCategoryRelPO newRelPo(Long id, Long productId, Long shopCategoryId) {
        final ProductShopCategoryRelPO po = new ProductShopCategoryRelPO();
        po.setId(id);
        po.setProductId(productId);
        po.setShopCategoryId(shopCategoryId);
        po.setTenantID(TENANT_A);
        return po;
    }
}