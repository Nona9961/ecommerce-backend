package com.nona.application.seller;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.replica.LastWriteMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.verify;

/**
 * 版本回滚写后埋点契约测试（回滚 = 商品内容变更、搜索可见内容变化，
 * 落库后标记写者本人——写后自读（read-your-writes）写用例）。
 * <p>
 * 契约：rollback 成功后调用 {@link LastWriteMarker#markWrite(Long)}
 * 标记<b>当前商家账号</b>（请求上下文身份）——商家回滚版本后 3s 内
 * 搜索立即可见内容重置。
 * <p>
 * fixture：真实仓储链路（H2 + 真实 Repository，同
 * ProductVersionUseCaseIntegrationAcTest）；身份经真实请求作用域绑定
 * （不 mock 上下文）。红阶段：埋点调用待落实（markWrite 未被调用），
 * 失败原因 = 实现缺失（埋点缺失）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductVersionRollbackWriteMarkerContractAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 操作人身份（请求作用域身份，埋点主体）
     */
    private static final String OPERATOR = "72001";

    /**
     * 被测商品编辑版本用例
     */
    @Autowired
    private ProductVersionUseCase productVersionUseCase;

    /**
     * 商品仓储（fixture 直建与读回）
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * 商品工厂（fixture 直建）
     */
    @Autowired
    private ProductFactory productFactory;

    /**
     * 编程式事务模板（fixture 装配）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 商品主表 JPA（清理）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 版本子表 JPA（清理）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * SKU 子表 JPA（清理）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

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
     * 写后窗口埋点（mock，断言回滚后当前商家被标记）
     */
    @MockitoBean
    private LastWriteMarker lastWriteMarker;

    /**
     * 每用例前提权清空商品及其子表。
     */
    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
        });
    }

    /**
     * happy：版本回滚 → 标记当前商家账号写后窗口（markWrite(商家 uid)）。
     */
    @Test
    @DisplayName("版本回滚后标记当前商家写后窗口")
    void rollback_marksMerchantWindow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.scope().setIdentity(OPERATOR);
            final Product product = saveProduct("初版");
            productVersionUseCase.recordEdit(product);

            productVersionUseCase.rollback(product.getId(), 1);

            verify(lastWriteMarker).markWrite(Long.valueOf(OPERATOR));
        });
    }

    /**
     * 商家侧直建草稿商品（回滚素材）。
     *
     * @param name 商品名
     * @return 商品聚合（ID 可引用）
     */
    private Product saveProduct(String name) {
        return tx.execute(status -> {
            final Product created = productFactory.createDraft(9101L, name, null, null, null);
            productRepository.save(created);
            return created;
        });
    }
}