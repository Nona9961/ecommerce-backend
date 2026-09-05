package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.factory.ShopFactory;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.ShopCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店铺仓储集成测试：聚合根主表（shop）存在性、从表（shop_category）变更集驱动
 * 落库、删除语义（主表+子表级联）、跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：聚合根必须有根表（shop 一行=一店，主键=店铺 ID，租户锚点
 * 不自引用）；仓储继承 DifferRepository，读=track 快照、save=变更集落库；
 * 从表以 shop_id（rootId）关联且 tenant=shopId——本店上下文只加载本店分类。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ShopRepositoryIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9001";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9002";

    /**
     * 店铺仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private ShopRepository shopRepository;

    /**
     * 店铺主表 JPA（断言根表行与清理）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 店铺分类子表 JPA（断言子表行与清理）
     */
    @Autowired
    private ShopCategoryJpaRepository shopCategoryJpaRepository;

    /**
     * 店铺聚合工厂
     */
    @Autowired
    private ShopFactory shopFactory;

    /**
     * 编程式事务模板（模拟用例层事务边界：删除/更新是写路径，需在事务内）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两张表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            shopCategoryJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
        });
    }

    /**
     * 新增：save 后主表存在一根行（店铺 ID 独立主键，global 无租户列），
     * 从表按聚合分类落库（tenant=A 由写门禁注入），聚合可整体读回（order 保留）。
     */
    @Test
    @DisplayName("新增店铺后主表存在根行且分类从表落库")
    void save_insertsRootRowAndCategoryRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = tx.execute(status -> {
                final Shop created = shopFactory.createShop("测试店铺", "logo.png", "简介");
                created.addCategory(shopFactory.createCategory(created, "零食"));
                created.addCategory(shopFactory.createCategory(created, "饮料"));
                shopRepository.save(created);
                return created;
            });
            assertThat(shop).isNotNull();

            assertThat(shopJpaRepository.existsById(shop.getId())).isTrue();
            final ShopPO rootPo = shopJpaRepository.findById(shop.getId()).orElseThrow();
            assertThat(rootPo.getName()).isEqualTo("测试店铺");
            assertThat(rootPo.getStatus().name()).isEqualTo("NORMAL");
            assertThat(shopCategoryJpaRepository.findByShopIdOrderByOrderNoAscIdAsc(shop.getId())).hasSize(2);

            final Shop loaded = shopRepository.getByID(shop.getId());
            assertThat(loaded).isNotNull();
            assertThat(loaded.getId()).isEqualTo(shop.getId());
            assertThat(loaded.categoriesOrdered()).hasSize(2);
            assertThat(loaded.categoriesOrdered().get(0).getName()).isEqualTo("零食");
            assertThat(loaded.categoriesOrdered().get(0).getOrder()).isEqualTo(1);
            assertThat(loaded.categoriesOrdered().get(1).getName()).isEqualTo("饮料");
            assertThat(loaded.categoriesOrdered().get(1).getOrder()).isEqualTo(2);
    
        });
}

    /**
     * 更新：快照基线后编辑店铺信息，save 只落变更行（根行字段更新，子表不动）。
     */
    @Test
    @DisplayName("编辑店铺信息后变更集驱动更新根行")
    void save_updateAppliesRootRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = shopFactory.createShop("原店铺", null, null);
            shopRepository.save(shop);

            final Shop loaded = shopRepository.getByID(shop.getId());
            loaded.updateInfo("新店铺", "logo-b.png", "新简介");
            shopRepository.save(loaded);

            final ShopPO rootPo = shopJpaRepository.findById(shop.getId()).orElseThrow();
            assertThat(rootPo.getName()).isEqualTo("新店铺");
            assertThat(rootPo.getLogo()).isEqualTo("logo-b.png");
            assertThat(rootPo.getDescription()).isEqualTo("新简介");
            assertThat(rootPo.getStatus().name()).isEqualTo("NORMAL");
    
        });
}

    /**
     * 更新：分类改名 → 从表行 name 更新、order 保持不变（不重排）。
     */
    @Test
    @DisplayName("分类改名后从表行更新且排序保持")
    void save_renameCategoryKeepsOrder() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = shopFactory.createShop("店铺", null, null);
            final ShopCategory first = shopFactory.createCategory(shop, "零食");
            final ShopCategory second = shopFactory.createCategory(shop, "饮料");
            shop.addCategory(first);
            shop.addCategory(second);
            shopRepository.save(shop);

            final Shop loaded = shopRepository.getByID(shop.getId());
            loaded.renameCategory(first.getId(), "休闲零食");
            shopRepository.save(loaded);

            final ShopCategoryPO firstPo = shopCategoryJpaRepository.findById(first.getId()).orElseThrow();
            assertThat(firstPo.getName()).isEqualTo("休闲零食");
            assertThat(firstPo.getOrderNo()).isEqualTo(1);
            assertThat(shopCategoryJpaRepository.findById(second.getId()).orElseThrow().getOrderNo()).isEqualTo(2);
    
        });
}

    /**
     * 更新：新增分类 → 从表插行且排序取当前最大 +1。
     */
    @Test
    @DisplayName("新增分类后从表插行且排序递增")
    void save_addCategoryInsertsRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = shopFactory.createShop("店铺", null, null);
            shop.addCategory(shopFactory.createCategory(shop, "零食"));
            shopRepository.save(shop);

            final Shop loaded = shopRepository.getByID(shop.getId());
            final ShopCategory added = shopFactory.createCategory(loaded, "饮料");
            loaded.addCategory(added);
            shopRepository.save(loaded);

            final ShopCategoryPO addedPo = shopCategoryJpaRepository.findById(added.getId()).orElseThrow();
            assertThat(addedPo.getName()).isEqualTo("饮料");
            assertThat(addedPo.getOrderNo()).isEqualTo(2);
            final Shop reloaded = shopRepository.getByID(shop.getId());
            assertThat(reloaded.categoriesOrdered()).hasSize(2);
            assertThat(reloaded.categoriesOrdered().get(1).getName()).isEqualTo("饮料");
    
        });
}

    /**
     * 更新：删除分类 → 从表删行、其余分类 order 保持（不重排）。
     */
    @Test
    @DisplayName("删除分类后从表删行且其余排序保持")
    void save_removeCategoryDeletesRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = shopFactory.createShop("店铺", null, null);
            final ShopCategory first = shopFactory.createCategory(shop, "零食");
            final ShopCategory second = shopFactory.createCategory(shop, "饮料");
            shop.addCategory(first);
            shop.addCategory(second);
            shopRepository.save(shop);

            final Shop loaded = shopRepository.getByID(shop.getId());
            loaded.removeCategory(first.getId());
            shopRepository.save(loaded);

            assertThat(shopCategoryJpaRepository.existsById(first.getId())).isFalse();
            assertThat(shopCategoryJpaRepository.findById(second.getId()).orElseThrow().getOrderNo()).isEqualTo(2);
    
        });
}

    /**
     * 删除：deleteByID 级联删从表行 + 根行，返回真实删除条数（1）；不存在返回 0。
     */
    @Test
    @DisplayName("删除店铺级联删从表与根表并返回真实行数")
    void deleteByID_cascadeDeletesRows() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shop = shopFactory.createShop("店铺", null, null);
            shop.addCategory(shopFactory.createCategory(shop, "零食"));
            shopRepository.save(shop);

            final int deleted = tx.execute(status -> shopRepository.deleteByID(shop.getId()));
            assertThat(deleted).isEqualTo(1);
            assertThat(shopJpaRepository.existsById(shop.getId())).isFalse();
            assertThat(shopCategoryJpaRepository.findByShopIdOrderByOrderNoAscIdAsc(shop.getId())).isEmpty();

            final int deletedAgain = tx.execute(status -> shopRepository.deleteByID(shop.getId()));
            assertThat(deletedAgain).isZero();
    
        });
}

    /**
     * 隔离：B 店铺租户上下文加载 A 店铺 → 主表（global）可见，但从表分类
     * 被租户过滤为空（fail-closed：跨店铺分类不可见）。
     */
    @Test
    @DisplayName("跨店铺加载分类不可见（fail-closed）")
    void loadForeignShop_categoriesInvisible() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shopA = shopFactory.createShop("店铺A", null, null);
            final ShopCategory categoryA = shopFactory.createCategory(shopA, "A店分类");
            shopA.addCategory(categoryA);
            shopRepository.save(shopA);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final Shop loadedAsB = shopRepository.getByID(shopA.getId());
                assertThat(loadedAsB).isNotNull();
                assertThat(loadedAsB.getName()).isEqualTo("店铺A");
                assertThat(loadedAsB.categoryCount()).isZero();
                assertThat(loadedAsB.getCategoryById(categoryA.getId())).isEmpty();
    
            });
        });
}

    /**
     * 归属：B 店铺租户上下文新增分类落库为 B 店归属（tenant 列=B），
     * 切回 A 上下文读不到 B 店分类。
     */
    @Test
    @DisplayName("分类归属写入当前店铺租户")
    void addCategoryUnderTenantB_writesTenantB() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final Shop shopB = shopFactory.createShop("店铺B", null, null);
            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final ShopCategory categoryB = shopFactory.createCategory(shopB, "B店分类");
                shopB.addCategory(categoryB);
                shopRepository.save(shopB);

                final ShopCategoryPO saved = shopCategoryJpaRepository.findById(categoryB.getId()).orElseThrow();
                assertThat(saved.getTenantID()).isEqualTo(TENANT_B);

                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    assertThat(shopCategoryJpaRepository.findByShopIdOrderByOrderNoAscIdAsc(shopB.getId())).isEmpty();
                    assertThat(shopCategoryJpaRepository.findById(categoryB.getId())).isEmpty();
    
                });
            });
        });
}
}