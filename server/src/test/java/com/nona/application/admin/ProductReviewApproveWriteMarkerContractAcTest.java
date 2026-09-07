package com.nona.application.admin;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.entity.SpecItem;
import com.nona.domain.catalog.entity.SpecTemplate;
import com.nona.domain.catalog.factory.ProductFactory;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.replica.LastWriteMarker;
import com.nona.inf.security.AuthUserCache;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.verify;

/**
 * 商品审核写后埋点契约测试（审核通过 = 商品转入在售、搜索可见性变化
 * ——TD-08 写后窗口，搜索可见性最直接相关写用例）。
 * <p>
 * 契约：审核通过（商品在售 + REVIEW_PASS 版本行同事务）成功后必须调用
 * {@link LastWriteMarker#markWrite(Long)} 标记<b>商品归属商家账号</b>
 * （数据变更直接归属方；主体语义同入驻审核设计决策）——
 * 商家提交商品审核后，通过瞬间 3s 内搜索立即可见自身商品在售。
 * <p>
 * fixture：商家侧直建待审商品（草稿 → 提交审核状态迁移，同既有审核流），
 * 平台用例走真实提权事务链路。红阶段：approve 埋点待落实（markWrite
 * 未被调用），失败原因 = 实现缺失（埋点缺失）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductReviewApproveWriteMarkerContractAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final long SHOP_A = 9101L;

    /**
     * 启用态平台分类 ID（完整商品装配用，无需预置分类行——approve 不
     * 校验引用）
     */
    private static final long CATEGORY_ENABLED = 93001L;

    /**
     * 启用态品牌 ID（完整商品装配用）
     */
    private static final long BRAND_ENABLED = 94001L;

    /**
     * 商品归属商家账号 ID（数据变更归属方、搜索调用者）
     */
    private static final long SELLER_UID = 72001L;

    /**
     * 平台审核人账号 ID（写操作执行者）
     */
    private static final long REVIEWER_UID = 9001L;

    /**
     * 平台商品审核用例（被测编排入口）
     */
    @Autowired
    private ProductReviewUseCase reviewUseCase;

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
     * 写后窗口埋点（mock，断言审核通过后商品归属商家被标记）
     */
    @MockitoBean
    private LastWriteMarker lastWriteMarker;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 账号-店铺关联 JPA（fixture：测试店铺归属商家反查行）
     */
    @Autowired
    private AccountShopRelJpaRepository relJpaRepository;

    /**
     * 每用例前提权清空商品及其子表与账号-店铺关联。
     */
    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
            relJpaRepository.deleteAll();
        });
    }

    /**
     * happy：商品审核通过 → 标记商品归属商家账号写后窗口
     * （markWrite(商家 uid)）。
     */
    @Test
    @DisplayName("商品审核通过后标记归属商家写后窗口")
    void approve_marksMerchantWriteWindow() {
        createShopOwnerRel();
        final AtomicLong productIdHolder = new AtomicLong();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(String.valueOf(SHOP_A));
            TrackingContext.scope().setIdentity(String.valueOf(SELLER_UID));
            productIdHolder.set(insertPendingReviewProduct("待审商品"));
        });
        final long productId = productIdHolder.get();

        TrackingContext.withScope(() -> reviewUseCase.approve(productId, REVIEWER_UID));

        // 埋点契约：审核通过 = 商品转入在售，商家 3s 内搜索立即可见
        verify(lastWriteMarker).markWrite(SELLER_UID);
    }

    /**
     * 预置店铺归属关联（SHOP_A → SELLER_UID）：approve 埋点主体经
     * {@code findByShopId} 反查定位（绿阶段机械补全：红线契约测试未
     * 预置反查所需行，而埋点契约要求 markWrite 归属商家）。
     */
    private void createShopOwnerRel() {
        final AccountShopRelPO rel = new AccountShopRelPO();
        rel.setId(IDUtils.generateID());
        rel.setAccountId(SELLER_UID);
        rel.setShopId(SHOP_A);
        relJpaRepository.save(rel);
    }

    /**
     * 商家侧直建完整待审商品：草稿完整装配（主图/属性/规格模板/双 SKU
     * 定价启用）→ 提交审核（状态迁移 PENDING_REVIEW，完整性守卫要求
     * 规格模板非空且启用 SKU）→ 落库（审核结论版本行由 approve 链路
     * 的 maxVersionNo+1 追加，无需预置版本行）。
     *
     * @param name 商品名
     * @return 商品 ID
     */
    private long insertPendingReviewProduct(String name) {
        return tx.execute(status -> {
            final Product draft = productFactory.createDraft(
                    SHOP_A, name, "描述", CATEGORY_ENABLED, BRAND_ENABLED);
            draft.addImage(new ProductImage(IDUtils.generateID(), draft.getId(), "/files/a.png", true));
            draft.addAttribute(new ProductAttribute(IDUtils.generateID(), draft.getId(), "材质", "纯棉"));
            draft.configureSpecTemplate(new SpecTemplate(
                    List.of(new SpecItem("颜色", List.of("黑", "白")))));
            priceAndEnableAll(draft);
            draft.submitForReview();
            productRepository.save(draft);
            return draft.getId();
        });
    }

    /**
     * 全部 SKU 定价并启用（直插构建用；价格 1999/2999）。
     *
     * @param product 商品聚合
     */
    private static void priceAndEnableAll(Product product) {
        final List<Sku> skus = product.skusOrdered();
        product.updateSkuPrice(skus.get(0).getId(), 1999L);
        product.updateSkuPrice(skus.get(1).getId(), 2999L);
        product.setSkuEnabled(skus.get(0).getId(), true);
        product.setSkuEnabled(skus.get(1).getId(), true);
    }
}