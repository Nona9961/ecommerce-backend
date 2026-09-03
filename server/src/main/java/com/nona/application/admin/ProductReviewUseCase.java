package com.nona.application.admin;

import com.nona.api.admin.ProductReviewItem;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.common.ProductLifecycleStatus;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.domain.catalog.factory.ProductEditVersionFactory;
import com.nona.domain.catalog.repo.ProductEditVersionRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.ProductSnapshotConvertor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 平台商品审核用例：待审/全量列表、审核通过、审核驳回（平台端，事务边界
 * 所在；审核 = 商品聚合状态机迁移，无独立审核单）。
 * <p>
 * 商品数据归店铺租户（tenant=shopId），平台视角跨店铺全集：列表为跨租户
 * 读（{@link CrossTenant}——查询放行只出现在应用层用例方法）；审核
 * （通过/驳回）为跨租户写路径，以 {@link TenantPrivilege#elevatedInTransaction}
 * 提权事务编排（加载任意店铺商品 → 聚合状态迁移与待审内容裁定 → 落库 +
 * 审核结论版本行追加，同一事务；主表行的租户归属显式承载于
 * ProductPO.shopId/tenantID 列，不依赖请求租户）。
 * <p>
 * 审核人身份由认证上下文提供（web 层从 ThreadContext 取当前账号 ID
 * 传入）；审核结论（审核人/时间/原因）经版本行落位：通过 → REVIEW_PASS
 * 行（快照 = 通过后的生效内容）；驳回 → REJECT 行（快照 = 驳回时刻生效
 * 内容 + 驳回原因）。审核动作仅对待审核商品合法（聚合状态机守卫，重复
 * 审核/未提交审批拒绝）；商户无本用例入口（角色路由隔离）。
 *
 * @author nona9961
 */
@Service
public class ProductReviewUseCase {

    /**
     * 商品仓储（跨店铺加载与落库；审核列表查询）
     */
    private final ProductRepository productRepository;

    /**
     * 商品编辑版本仓储（审核结论版本行追加）
     */
    private final ProductEditVersionRepository editVersionRepository;

    /**
     * 商品编辑版本工厂（审核结论版本行创建）
     */
    private final ProductEditVersionFactory editVersionFactory;

    /**
     * 商品内容快照转换器（审核时刻生效内容快照序列化）
     */
    private final ProductSnapshotConvertor snapshotConvertor;

    /**
     * 提权工具（审核写路径 elevatedInTransaction：读放行 + 写放行合一）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造平台商品审核用例。
     *
     * @param productRepository      商品仓储
     * @param editVersionRepository  商品编辑版本仓储
     * @param editVersionFactory     商品编辑版本工厂
     * @param snapshotConvertor      商品内容快照转换器
     * @param tenantPrivilege        提权工具（跨租户写放行）
     * @param transactionTemplate    事务模板（提权事务）
     */
    public ProductReviewUseCase(ProductRepository productRepository,
                                ProductEditVersionRepository editVersionRepository,
                                ProductEditVersionFactory editVersionFactory,
                                ProductSnapshotConvertor snapshotConvertor,
                                TenantPrivilege tenantPrivilege,
                                TransactionTemplate transactionTemplate) {
        this.productRepository = productRepository;
        this.editVersionRepository = editVersionRepository;
        this.editVersionFactory = editVersionFactory;
        this.snapshotConvertor = snapshotConvertor;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 商品审核列表（分页；按商品状态过滤可选，null 表示全部；管理员视角
     * 跨店铺全集——跨租户读放行只出现在本用例方法）。按创建序（先创建
     * 的先审）。
     *
     * @param status 商品状态过滤；null 表示全部
     * @param query  分页请求
     * @return 商品条目分页结果
     */
    @CrossTenant
    public PageResult<ProductReviewItem> list(ProductLifecycleStatus status, PageQuery query) {
        final int offset = Math.toIntExact(query.offset());
        final int limit = query.pageSize();
        final long total;
        final List<Product> products;
        if (status == null) {
            total = productRepository.countAll();
            products = productRepository.listAllPaged(offset, limit);
        } else {
            final ProductStatus domainStatus = toDomainStatus(status);
            total = productRepository.countByStatus(domainStatus);
            products = productRepository.listByStatusPaged(domainStatus, offset, limit);
        }
        return PageResult.of(products.stream().map(ProductReviewUseCase::toItem).toList(),
                total, query);
    }

    /**
     * 审核通过（平台）：提权事务内加载任意店铺商品 → 聚合状态机迁移
     * （待审核 → 在售；待审草稿内容覆盖正式内容）→ 保存 → 追加审核结论
     * 版本行（REVIEW_PASS，快照 = 通过后的生效内容，操作人 = 审核人）。
     * 待审内容与审核结论版本行同事务（all-or-nothing）。目标商品不存在
     * 按不存在呈现（404）。
     *
     * @param productId  商品 ID（管理员视角任意店铺）
     * @param reviewerId 审核人账号 ID（认证上下文）
     */
    public void approve(Long productId, Long reviewerId) {
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final Product product = require(productId);
                product.approve();
                productRepository.save(product);
                final String snapshotJson = snapshotConvertor.toSnapshotJson(product);
                final int nextVersionNo = editVersionRepository.maxVersionNo(productId) + 1;
                editVersionRepository.append(editVersionFactory.createReviewPass(
                        productId, nextVersionNo, snapshotJson, String.valueOf(reviewerId)));
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("审核通过事务失败", e);
        }
    }

    /**
     * 审核驳回（平台）：提权事务内加载任意店铺商品 → 聚合状态机迁移
     * （待审核 → 草稿，待审草稿作废）→ 保存 → 追加审核结论版本行
     * （REJECT，快照 = 驳回时刻生效内容，操作人 = 审核人，原因 = 驳回
     * 原因）。驳回不改变生效内容（买家继续可见旧内容），商家回草稿后可
     * 修改重提（修改走草稿直改路径）。
     *
     * @param productId  商品 ID（管理员视角任意店铺）
     * @param reason     驳回原因（必填非空，聚合守卫；商家据此修改重提）
     * @param reviewerId 审核人账号 ID（认证上下文）
     */
    public void reject(Long productId, String reason, Long reviewerId) {
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final Product product = require(productId);
                product.reject(reason);
                productRepository.save(product);
                final String snapshotJson = snapshotConvertor.toSnapshotJson(product);
                final int nextVersionNo = editVersionRepository.maxVersionNo(productId) + 1;
                editVersionRepository.append(editVersionFactory.createReject(
                        productId, nextVersionNo, snapshotJson,
                        String.valueOf(reviewerId), reason));
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("审核驳回事务失败", e);
        }
    }

    /**
     * 加载商品并断言存在（提权事务内加载任意店铺商品；不存在按 404 呈现）。
     *
     * @param productId 商品 ID
     * @return 商品聚合
     */
    private Product require(Long productId) {
        final Product product = productRepository.getByID(productId);
        if (product == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), "商品不存在");
        }
        return product;
    }

    /**
     * 领域商品 → 审核列表条目（派生概要：图片数/启用 SKU 数供审核人判断
     * 资料完整度）。
     *
     * @param product 商品聚合
     * @return 审核条目
     */
    private static ProductReviewItem toItem(Product product) {
        return new ProductReviewItem(
                product.getId(),
                product.getShopId(),
                product.getName(),
                product.getCategoryId(),
                product.getBrandId(),
                product.imagesOrdered().size(),
                (int) product.skusOrdered().stream().filter(sku -> sku.isEnabled()).count(),
                toContractStatus(product.getStatus()));
    }

    /**
     * 领域状态 → 契约状态（映射点收敛在用例）。
     *
     * @param status 领域状态
     * @return 契约状态
     */
    private static ProductLifecycleStatus toContractStatus(ProductStatus status) {
        return switch (status) {
            case DRAFT -> ProductLifecycleStatus.DRAFT;
            case PENDING_REVIEW -> ProductLifecycleStatus.PENDING_REVIEW;
            case ON_SALE -> ProductLifecycleStatus.ON_SALE;
            case DELISTED -> ProductLifecycleStatus.DELISTED;
        };
    }

    /**
     * 契约状态 → 领域状态（列表过滤映射点）。
     *
     * @param status 契约状态
     * @return 领域状态
     */
    private static ProductStatus toDomainStatus(ProductLifecycleStatus status) {
        return switch (status) {
            case DRAFT -> ProductStatus.DRAFT;
            case PENDING_REVIEW -> ProductStatus.PENDING_REVIEW;
            case ON_SALE -> ProductStatus.ON_SALE;
            case DELISTED -> ProductStatus.DELISTED;
        };
    }
}