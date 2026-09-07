package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.ProductAttributePO;
import com.nona.inf.persistence.po.catalog.ProductImagePO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品仓储集成测试：聚合根主表（product）存在性、从表（product_image /
 * product_attribute）变更集驱动落库（增/删/改/设主图）、删除级联、
 * 分页/计数、引用存在性查询与跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：聚合根必须有权表（product 一行=一商品，主键=商品 ID，
 * tenant=shopId）；仓储继承 DifferRepository，读=track 快照、save=变更集
 * 落库；从表经 getOther 加载（租户过滤仅含本店行）、变更集驱动插删改；
 * 集合子实体 identifier 提取器（ProductImage/ProductAttribute → getId）
 * 已在 application-dev.yml 注册。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductRepositoryIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9102";

    /**
     * 商品仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * 商品主表 JPA（断言根表行与清理）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 图片子表 JPA（断言子表行与清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * 属性子表 JPA（断言子表行与清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 商品聚合工厂
     */
    @Autowired
    private ProductFactory productFactory;

    /**
     * 编程式事务模板（模拟用例层事务边界：写路径需在事务内）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空三张表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
        });
    }

    /**
     * 新增：save 后主表存在一根行（tenant=A 由写门禁注入），图片/属性从表
     * 按聚合集合落库，聚合可整体读回（保序 + 主图标记保留）。
     */
    @Test
    @DisplayName("新增草稿后主表与图片/属性从表落库且整体读回")
    void save_insertsRootAndChildRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product created = createProductWithImagesAndAttributes("测试商品");

            assertThat(productJpaRepository.existsById(created.getId())).isTrue();
            final ProductPO rootPo = productJpaRepository.findById(created.getId()).orElseThrow();
            assertThat(rootPo.getName()).isEqualTo("测试商品");
            assertThat(rootPo.getShopId()).isEqualTo(9101L);
            assertThat(rootPo.getStatus().name()).isEqualTo("DRAFT");
            assertThat(imageJpaRepository.findByProductIdOrderByIdAsc(created.getId())).hasSize(2);
            assertThat(attributeJpaRepository.findByProductIdOrderByIdAsc(created.getId())).hasSize(2);

            final Product loaded = productRepository.getByID(created.getId());
            assertThat(loaded).isNotNull();
            assertThat(loaded.imagesOrdered()).hasSize(2);
            assertThat(loaded.imagesOrdered().get(0).getUrl()).isEqualTo("/files/a.png");
            assertThat(loaded.imagesOrdered().get(0).isPrimary()).isFalse();
            assertThat(loaded.imagesOrdered().get(1).isPrimary()).isTrue();
            assertThat(loaded.attributesOrdered()).hasSize(2);
            assertThat(loaded.attributesOrdered().get(0).getKey()).isEqualTo("材质");
    
        });
}

    /**
     * 更新：改名/改引用 → 根行整行更新。
     */
    @Test
    @DisplayName("编辑主体后变更集驱动更新根行")
    void save_updateAppliesRootRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = createProductWithImagesAndAttributes("原商品");
            final Product loaded = productRepository.getByID(product.getId());

            loaded.updateInfo("新商品名", "新描述", 7701L, 8801L);
            productRepository.save(loaded);

            final ProductPO rootPo = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(rootPo.getName()).isEqualTo("新商品名");
            assertThat(rootPo.getDescription()).isEqualTo("新描述");
            assertThat(rootPo.getCategoryId()).isEqualTo(7701L);
            assertThat(rootPo.getBrandId()).isEqualTo(8801L);
    
        });
}

    /**
     * 更新：新增图片插入、设主图清除原主图、删图删行——同一变更集一次保存。
     */
    @Test
    @DisplayName("图片增删与设主图变更集驱动落库")
    void save_imageManagementAppliesChanges() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = createProductWithImagesAndAttributes("测试商品");

            final Product loaded = productRepository.getByID(product.getId());
            final ProductImage third = productFactory.createImage(loaded, "/files/c.png", false);
            loaded.addImage(third);
            loaded.setPrimaryImage(product.imagesOrdered().get(0).getId());
            productRepository.save(loaded);

            final List<ProductImagePO> rows = imageJpaRepository.findByProductIdOrderByIdAsc(product.getId());
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).isPrimary()).isTrue();
            assertThat(rows.get(1).isPrimary()).isFalse();
            assertThat(rows.get(2).isPrimary()).isFalse();

            final Product loaded2 = productRepository.getByID(product.getId());
            loaded2.removeImage(third.getId());
            productRepository.save(loaded2);
            assertThat(imageJpaRepository.findByProductIdOrderByIdAsc(product.getId())).hasSize(2);
    
        });
}

    /**
     * 更新：属性增/改/删变更集驱动落库。
     */
    @Test
    @DisplayName("属性增改删变更集驱动落库")
    void save_attributeManagementAppliesChanges() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = createProductWithImagesAndAttributes("测试商品");

            final Product loaded = productRepository.getByID(product.getId());
            loaded.addAttribute(productFactory.createAttribute(loaded, "颜色", "黑"));
            loaded.updateAttribute(product.attributesOrdered().get(0).getId(), "面料", "涤纶");
            productRepository.save(loaded);

            final List<ProductAttributePO> rows = attributeJpaRepository.findByProductIdOrderByIdAsc(product.getId());
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).getAttrKey()).isEqualTo("面料");
            assertThat(rows.get(0).getAttrValue()).isEqualTo("涤纶");
            assertThat(rows.get(2).getAttrKey()).isEqualTo("颜色");

            final Product loaded2 = productRepository.getByID(product.getId());
            loaded2.removeAttribute(product.attributesOrdered().get(1).getId());
            productRepository.save(loaded2);
            assertThat(attributeJpaRepository.findByProductIdOrderByIdAsc(product.getId())).hasSize(2);
    
        });
}

    /**
     * 删除：deleteByID 级联删除图片/属性从表 + 主表，返回真实条数 1；
     * 不存在返回 0。
     */
    @Test
    @DisplayName("删除草稿级联清理从表并返回真实条数")
    void deleteByID_cascadesChildren() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = createProductWithImagesAndAttributes("测试商品");

            final int deleted = tx.execute(status -> productRepository.deleteByID(product.getId()));

            assertThat(deleted).isEqualTo(1);
            assertThat(productJpaRepository.existsById(product.getId())).isFalse();
            assertThat(imageJpaRepository.findByProductIdOrderByIdAsc(product.getId())).isEmpty();
            assertThat(attributeJpaRepository.findByProductIdOrderByIdAsc(product.getId())).isEmpty();
            final Integer deletedAgain = tx.execute(status -> productRepository.deleteByID(product.getId()));
            assertThat(deletedAgain).isEqualTo(0);
    
        });
}

    /**
     * 分页：listByShopPaged 按店铺隔离、新商品在前、count 准确。
     */
    @Test
    @DisplayName("分页列表按店铺隔离且新商品在前")
    void listByShopPaged_isolationAndOrdering() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product first = tx.execute(status -> {
                final Product created = productFactory.createDraft(9101L, "商品一", null, null, null);
                productRepository.save(created);
                return created;
            });
            final Product second = tx.execute(status -> {
                final Product created = productFactory.createDraft(9101L, "商品二", null, null, null);
                productRepository.save(created);
                return created;
            });
            tx.execute(status -> {
                final Product other = productFactory.createDraft(9102L, "他店商品", null, null, null);
                productRepository.save(other);
                return other;
            });

            assertThat(productRepository.countByShop(9101L)).isEqualTo(2);
            final List<Product> page = productRepository.listByShopPaged(9101L, 0, 10);
            assertThat(page).hasSize(2);
            assertThat(page.get(0).getName()).isEqualTo(second.getName());
            assertThat(page.get(1).getName()).isEqualTo(first.getName());
    
        });
}

    /**
     * 引用查询：existsByCategoryId/existsByBrandId 在租户过滤下命中本店引用。
     */
    @Test
    @DisplayName("类目/品牌引用存在性查询命中本店行")
    void referenceExists_hitsTenantScopedRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            tx.execute(status -> {
                final Product created = productFactory.createDraft(9101L, "测试商品", null, 7701L, 8801L);
                productRepository.save(created);
                return created;
            });

            assertThat(productRepository.existsByCategoryId(7701L)).isTrue();
            assertThat(productRepository.existsByBrandId(8801L)).isTrue();
            assertThat(productRepository.existsByCategoryId(9999L)).isFalse();
            assertThat(productRepository.existsByBrandId(9999L)).isFalse();
    
        });
}

    /**
     * 隔离：B 店铺上下文读不到 A 店铺商品（fail-closed）；B 的列表/计数不含 A 行。
     */
    @Test
    @DisplayName("跨店铺访问按不存在呈现且列表隔离")
    void crossTenantAccess_failClosed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product product = tx.execute(status -> {
                final Product created = productFactory.createDraft(9101L, "A店商品", null, null, null);
                productRepository.save(created);
                return created;
            });

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                assertThat(productRepository.getByID(product.getId())).isNull();
                assertThat(productRepository.listByShopPaged(9101L, 0, 10)).isEmpty();
                assertThat(productRepository.countByShop(9101L)).isZero();
                assertThat(productRepository.existsByCategoryId(7701L)).isFalse();
    
            });
        });
}

    /**
     * 创建带图片/属性的商品（事务内落库）。
     *
     * @param name 商品名称
     * @return 已持久化商品
     */
    private Product createProductWithImagesAndAttributes(String name) {
        return tx.execute(status -> {
            final Product created = productFactory.createDraft(9101L, name, "描述", null, null);
            final ProductImage first = productFactory.createImage(created, "/files/a.png", false);
            final ProductImage second = productFactory.createImage(created, "/files/b.png", true);
            created.addImage(first);
            created.addImage(second);
            created.addAttribute(productFactory.createAttribute(created, "材质", "纯棉"));
            created.addAttribute(productFactory.createAttribute(created, "尺码", "L"));
            productRepository.save(created);
            return created;
        });
    }
}