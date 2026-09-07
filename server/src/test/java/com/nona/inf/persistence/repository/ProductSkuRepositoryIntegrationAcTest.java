package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品仓储 SKU 扩展契约测试：规格模板 + SKU 集随聚合整体持久化
 * （product_sku 从表，变更集分发扩展点）、模板变更保留语义落库、空模板
 * 清集、跨店铺 fail-closed（SKU 与商品同租户，跨店铺商品行不可见）。
 * <p>
 * 对应持久化形态：聚合根主表 product（id=商品 ID，tenant=shopId）+ 从表
 * product_sku（tenant=shopId，以 productId 关联主表；product_sku 行由
 * DifferRepository getOther 加载、变更集驱动插删改——图片/属性分发模式
 * 的第三集合扩展；identifier 提取器（Sku → getId）注册为持久化配置项）。
 * <p>
 * 红状态说明：模板配置/重建为契约声明（方法体抛
 * UnsupportedOperationException）——全部用例红，红因 = 实现缺失；
 * 本轮不触碰 product_sku 表级断言（PO 与 JPA 仓储随实现阶段就位，
 * 其唯一约束兜底（product_id, spec_hash）由实现阶段测试承接）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductSkuRepositoryIntegrationAcTest {

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
     * 商品聚合工厂（创建草稿）
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
     * happy：配置模板 → 保存 → 整体读回（SKU 集随聚合持久化，含派生
     * 摘要与默认态，模板读回一致）。
     */
    @Test
    @DisplayName("配置模板后保存读回完整 SKU 集")
    void save_configuredTemplatePersistsSkuSet() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product created = createDraft("测试商品");

            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(
                        template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL")));
                productRepository.save(loaded);
                return null;
            });

            final Product reloaded = productRepository.getByID(created.getId());
            assertThat(reloaded.getSpecTemplate()).hasValueSatisfying(template -> {
                assertThat(template.combinationCount()).isEqualTo(4);
            });
            assertThat(reloaded.skusOrdered()).hasSize(4);
            assertThat(reloaded.skusOrdered()).extracting(Sku::getSpecSummary)
                    .containsExactly(
                            "颜色:黑,尺寸:L",
                            "颜色:黑,尺寸:XL",
                            "颜色:白,尺寸:L",
                            "颜色:白,尺寸:XL");
            assertThat(reloaded.skusOrdered()).allSatisfy(sku -> {
                assertThat(sku.getProductId()).isEqualTo(created.getId());
                assertThat(sku.getPrice()).isNull();
                assertThat(sku.isEnabled()).isFalse();
            });
    
        });
}

    /**
     * critical：模板变更（新增规格值）后保存读回——同组合 SKU 价格与
     * ID 存活、新增组合为默认态。
     */
    @Test
    @DisplayName("模板变更保留价格与 ID 的变更集落库读回")
    void save_templateChangeKeepsPriceAndIdentity() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product created = createDraft("测试商品");
            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(template(dim("颜色", "黑", "白"), dim("尺寸", "L")));
                productRepository.save(loaded);
                return null;
            });

            final Long blackLId = tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                final Long id = loaded.skusOrdered().get(0).getId();
                loaded.updateSkuPrice(id, 100L);
                productRepository.save(loaded);
                return id;
            });

            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(
                        template(dim("颜色", "黑", "白", "蓝"), dim("尺寸", "L")));
                productRepository.save(loaded);
                return null;
            });

            final Product reloaded = productRepository.getByID(created.getId());
            assertThat(reloaded.skusOrdered()).hasSize(3);
            final Sku survived = reloaded.getSkuById(blackLId).orElseThrow();
            assertThat(survived.getPrice()).isEqualTo(100L);
            final Sku added = reloaded.skusOrdered().stream()
                    .filter(sku -> sku.getSpecSummary().equals("颜色:蓝,尺寸:L"))
                    .findFirst().orElseThrow();
            assertThat(added.getPrice()).isNull();
    
        });
}

    /**
     * critical：空模板配置后保存读回——SKU 集清空、模板为空形态。
     */
    @Test
    @DisplayName("空模板清空 SKU 集的变更集落库读回")
    void save_emptyTemplateClearsSkuSet() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product created = createDraft("测试商品");
            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(template(dim("颜色", "黑", "白")));
                productRepository.save(loaded);
                return null;
            });

            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(new SpecTemplate(null));
                productRepository.save(loaded);
                return null;
            });

            final Product reloaded = productRepository.getByID(created.getId());
            assertThat(reloaded.skusOrdered()).isEmpty();
            assertThat(reloaded.getSpecTemplate()).hasValueSatisfying(SpecTemplate::isEmpty);
            assertThat(reloaded.skusOrdered()).isEmpty();
    
        });
}

    /**
     * error：跨店铺访问不可见（fail-closed）——A 店铺商品配置模板保存后，
     * B 店铺上下文按不存在呈现（不泄露归属），列表与计数均不含 A 行。
     */
    @Test
    @DisplayName("跨店铺 SKU 与商品一并不可见")
    void crossTenantAccess_failClosed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Product created = createDraft("测试商品");
            tx.execute(status -> {
                final Product loaded = productRepository.getByID(created.getId());
                loaded.configureSpecTemplate(template(dim("颜色", "黑", "白")));
                productRepository.save(loaded);
                return null;
            });

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);

                assertThat(productRepository.getByID(created.getId())).isNull();
                assertThat(productRepository.listByShopPaged(9101L, 0, 10)).isEmpty();
                assertThat(productRepository.countByShop(9101L)).isZero();
    
            });
        });
}

    /**
     * 创建工作在事务内落库的草稿商品。
     *
     * @param name 商品名称
     * @return 已持久化商品
     */
    private Product createDraft(String name) {
        return tx.execute(status -> {
            final Product created = productFactory.createDraft(9101L, name, "描述", null, null);
            productRepository.save(created);
            return created;
        });
    }

    /**
     * 构造规格维度。
     *
     * @param name   维度名
     * @param values 维度值
     * @return 维度
     */
    private static SpecItem dim(String name, String... values) {
        return new SpecItem(name, Arrays.asList(values));
    }

    /**
     * 构造规格模板。
     *
     * @param items 维度列表
     * @return 模板
     */
    private static SpecTemplate template(SpecItem... items) {
        return new SpecTemplate(Arrays.asList(items));
    }
}