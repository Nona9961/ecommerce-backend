package com.nona.application.support;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.SelloutEvent;
import com.nona.inf.context.TenantPrivilege;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 售罄自动下架消费方（C8/S4.5 ②——inventory 售罄事件 → catalog 自动
 * 下架）。
 * <p>
 * 装配形态（对齐 WU-23 事件链路契约）：
 * <ul>
 *     <li>订阅：{@link TransactionalEventListener} AFTER_COMMIT 监听库存
 *         事务提交后的 {@link SelloutEvent}（消费方只见已提交库存状态）；
 *         与既有日志消费（InventoryEventLogListener）并存——同一事件多
 *         监听器天然支持，互不干扰；</li>
 *     <li>异步：监听方法把自动下架任务提交到库存事件执行器
 *         （stockEventExecutor——上下文传播装饰器随任务携带发布线程的
 *         租户快照；任务内编排自管事务与放行，不依赖请求上下文）；</li>
 *     <li>消费失败不影响库存主链路（任务体双层容错，同 WU-23 日志消费
 *         形制）。</li>
 * </ul>
 * 编排语义（绿阶段落点，本类 javadoc 即契约）：
 * <ol>
 *     <li>skuId → 商品定位：经商品仓储反查归属商品 ID（跨店铺读放行/
 *         提权上下文内执行——SKU 已不存在/不可见返回空并静默跳过）；</li>
 *     <li>状态守卫：商品非 ON_SALE 静默跳过（幂等：重复事件/已下架/
 *         草稿/待审核不动作——事件消费为最终一致联动，静默跳过语义防
 *         重复消费与乱序伤害）；</li>
 *     <li>售罄判定（商品维度）：商品<b>全部启用 SKU</b> 可售量均为 0
 *         才自动下架（部分 SKU 售罄只置灰不可选，商品继续在售——经库存
 *         门面 queryAvailable 读取当前可售，消费时点判定避免「补货后旧
 *         事件误下架」）；</li>
 *     <li>迁移：聚合 delist（在售 → 已下架）→ 变更集落库——下架编排在
 *         elevatedInTransaction 提权事务内（事件消费无请求视角，写 tenant
 *         表须提权 + PO 租户由写门禁注入/显式承载），读放行与写放行
 *         合一。</li>
 * </ol>
 *
 * @author nona9961
 */
@Slf4j
@Component
public class ProductAutoDelistListener {

    /**
     * 商品仓储（SKU 反查/商品加载/落库）
     */
    private final ProductRepository productRepository;

    /**
     * 库存门面（全部启用 SKU 可售量读——售罄判定输入）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 提权工具（自动下架写路径 elevatedInTransaction：读放行 + 写放行合一）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 事件异步执行器（库存事件执行器——与 WU-23 事件消费共用装配点与
     * 上下文传播装饰器）
     */
    private final Executor stockEventExecutor;

    /**
     * 构造售罄自动下架消费方。
     *
     * @param productRepository  商品仓储
     * @param inventoryFacade    库存门面
     * @param tenantPrivilege    提权工具
     * @param transactionTemplate 事务模板
     * @param stockEventExecutor 库存事件异步执行器
     */
    public ProductAutoDelistListener(ProductRepository productRepository,
                                     InventoryFacade inventoryFacade,
                                     TenantPrivilege tenantPrivilege,
                                     TransactionTemplate transactionTemplate,
                                     @Qualifier("stockEventExecutor") Executor stockEventExecutor) {
        this.productRepository = productRepository;
        this.inventoryFacade = inventoryFacade;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
        this.stockEventExecutor = stockEventExecutor;
    }

    /**
     * 售罄事件自动下架消费（事务提交后投递——无事务的发布路径不触发）：
     * 提交自动下架任务到事件执行器异步执行；提交/执行失败均捕获为告警，
     * 不影响库存主链路。
     *
     * @param event 售罄事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSellout(SelloutEvent event) {
        final Long skuId = event.getPayload().skuId();
        try {
            stockEventExecutor.execute(() -> {
                try {
                    autoDelistOnSellout(skuId);
                } catch (final RuntimeException ex) {
                    log.warn("[sellout-auto-delist] async consume failed: skuId={}, msg={}",
                            skuId, ex.getMessage(), ex);
                }
            });
        } catch (final RuntimeException ex) {
            log.warn("[sellout-auto-delist] async submit failed: skuId={}, msg={}",
                    skuId, ex.getMessage(), ex);
        }
    }

    /**
     * 自动下架编排（语义见类注）：skuId → 商品定位 → 非 ON_SALE 静默
     * 跳过 → 全部启用 SKU 可售均为 0（经库存门面消费时点判定，缺行按
     * 0）才聚合迁移落库——整体在提权事务内（事件消费无请求视角，读放行
     * 与写放行合一；事务回滚由异常透传承载）。
     *
     * @param skuId 售罄 SKU ID
     */
    public void autoDelistOnSellout(Long skuId) {
        if (skuId == null) {
            return;
        }
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final Long productId = productRepository.findProductIdBySkuId(skuId);
                if (productId == null) {
                    return null;
                }
                final Product product = productRepository.getByID(productId);
                if (product == null || product.getStatus() != ProductStatus.ON_SALE) {
                    return null;
                }
                final List<Sku> enabledSkus = product.skusOrdered().stream()
                        .filter(Sku::isEnabled)
                        .toList();
                if (enabledSkus.isEmpty()) {
                    return null;
                }
                final Map<Long, Integer> availableBySku = inventoryFacade
                        .queryAvailable(enabledSkus.stream().map(Sku::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(InventoryAvailable::skuId, InventoryAvailable::available));
                final boolean allSoldOut = enabledSkus.stream()
                        .allMatch(sku -> availableBySku.getOrDefault(sku.getId(), 0) == 0);
                if (!allSoldOut) {
                    return null;
                }
                product.delist();
                productRepository.save(product);
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("售罄自动下架事务失败", e);
        }
    }
}
