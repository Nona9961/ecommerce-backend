package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.repo.SkuProductView;
import com.nona.domain.catalog.repo.SkuProductViewRepository;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.po.catalog.SkuPO;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SKU 商品摘要投影仓储实现（WU-60 冻结契约，红阶段骨架）。
 * <p>
 * 投影装配路径（绿阶段实现）：SkuJpaRepository 按 SKU ID 集合装载
 * SKU 行（findByIds，租户过滤由 JPA 多租户机制注入）→ 按归属商品分组
 * → ProductJpaRepository 装载商品主体（findByIds）→ 组装
 * {@link SkuProductView} 行（specSummary 直接取自 SKU 行展示列，
 * productName 取自商品主体）。读路径不登记变更追踪（只读呈现纪律）。
 * <p>
 * 租户过滤：本投影与库存行查询同店铺口径——跨店铺 SKU 不命中
 * （fail-closed，归属不泄露）。
 *
 * @author nona9961
 */
@Component
public class SkuProductViewRepositoryImpl implements SkuProductViewRepository {

    /**
     * SKU 行持久化接入（inf 层装配，域接口无对应面——投影直读）
     */
    private final SkuJpaRepository skuJpaRepository;

    /**
     * 商品主体持久化接入（同上）
     */
    private final ProductJpaRepository productJpaRepository;

    /**
     * 构造投影仓储实现。
     *
     * @param skuJpaRepository      SKU 行仓储
     * @param productJpaRepository  商品主体仓储
     */
    public SkuProductViewRepositoryImpl(SkuJpaRepository skuJpaRepository,
                                        ProductJpaRepository productJpaRepository) {
        this.skuJpaRepository = skuJpaRepository;
        this.productJpaRepository = productJpaRepository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 红阶段占位：实现缺失（WU-60 绿阶段接线）。
     */
    @Override
    public List<SkuProductView> listBySkuIds(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        // ① SKU 行一次装载（租户过滤由 JPA discriminator 机制注入——
        // 跨店铺 SKU 不命中，fail-closed，与库存行查询同店铺口径）
        final List<SkuPO> skus = skuJpaRepository.findAllById(skuIds);
        if (skus.isEmpty()) {
            return List.of();
        }
        // ② 商品主体按归属商品 ID 集合一次装载（不逐 SKU getByID——
        // 避免 N+1；页行 ≤100 单查询收敛）
        final Set<Long> productIds = skus.stream().map(SkuPO::getProductId)
                .collect(Collectors.toSet());
        final Map<Long, ProductPO> products = productJpaRepository.findAllById(productIds)
                .stream().collect(Collectors.toMap(ProductPO::getId, Function.identity()));
        // ③ 组装投影行：specSummary 直接取自 SKU 行展示列，productName
        // 取自商品主体；商品主体行缺失（引用悬挂 = 装配数据异常）的 SKU
        // 剔除出结果——调用方按「join 缺失」fail-closed 呈现（前端
        // productId/productName 非空契约，不静默降级 null 展示）
        return skus.stream()
                .filter(sku -> products.containsKey(sku.getProductId()))
                .map(sku -> new SkuProductView(
                        sku.getId(),
                        sku.getProductId(),
                        products.get(sku.getProductId()).getName(),
                        sku.getSpecSummary()))
                .toList();
    }
}