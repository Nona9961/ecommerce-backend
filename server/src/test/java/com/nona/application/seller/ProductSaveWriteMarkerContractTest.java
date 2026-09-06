package com.nona.application.seller;

import com.nona.api.seller.ProductDraftRequest;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.verify;

/**
 * 商品保存写后埋点契约测试（商品保存 = 搜索可见内容变更，落库后
 * 标记写者本人——TD-08 read-your-writes）。
 * <p>
 * 契约：商品写方法（createDraft/update/delete/图片/属性/规格/SKU/
 * 提交审核等全部写路径）成功后调用
 * {@link LastWriteMarker#markWrite(Long)} 标记<b>当前商家账号</b>
 * （请求上下文身份，{@link TenantContextAccessor#getIdentity()}——写者
 * 本人即搜索调用者，最典型写后自读场景）——商家保存后 3s 内搜索立
 * 即看到自身商品变更。
 * <p>
 * fixture：真实仓储链路（H2 + 真实 Repository，同
 * ProductUseCaseVersionTraceIntegrationTest）；上下文身份以 mock
 * 返回固定商家账号。红阶段：埋点调用待落实（markWrite 未被调用），
 * 失败原因 = 实现缺失（埋点缺失）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductSaveWriteMarkerContractTest {

    /**
     * 测试店铺 A（店铺 ID 即租户 ID）
     */
    private static final long SHOP_A = 9101L;

    /**
     * 当前商家账号（请求上下文身份）
     */
    private static final long OPERATOR_UID = 72001L;

    /**
     * 商品草稿用例（被测写入口）
     */
    @Autowired
    private ProductUseCase productUseCase;

    /**
     * 商品仓储（update fixture 装配）
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * 商品工厂（update fixture 装配）
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
     * 写后窗口埋点（mock，断言保存后写者本人被标记）
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
     * happy：创建草稿 → 标记写者本人账号写后窗口（markWrite(商家 uid)，
     * 身份 = 请求作用域身份）。
     */
    @Test
    @DisplayName("创建商品草稿后标记写者写后窗口")
    void createDraft_marksWriterWindow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(String.valueOf(OPERATOR_UID));

            productUseCase.createDraft(SHOP_A, new ProductDraftRequest("新品手机", null, null, null));

            verify(lastWriteMarker).markWrite(OPERATOR_UID);
        });
    }

    /**
     * happy：更新商品主体 → 标记写者本人账号写后窗口。
     */
    @Test
    @DisplayName("更新商品主体后标记写者写后窗口")
    void update_marksWriterWindow() {
        final AtomicLong productIdHolder = new AtomicLong();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(String.valueOf(OPERATOR_UID));
            productIdHolder.set(insertDraftProduct("基线商品"));
        });
        final long productId = productIdHolder.get();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(String.valueOf(OPERATOR_UID));

            productUseCase.update(productId, new ProductDraftRequest("进阶商品", null, null, null));

            verify(lastWriteMarker).markWrite(OPERATOR_UID);
        });
    }

    /**
     * 商家侧直建草稿商品（update 目标）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long insertDraftProduct(String name) {
        return tx.execute(status -> {
            final Product draft = productFactory.createDraft(SHOP_A, name, null, null, null);
            productRepository.save(draft);
            return draft.getId();
        });
    }
}