package com.nona.application.admin;

import com.nona.api.admin.BrandItem;
import com.nona.api.admin.BrandRequest;
import com.nona.api.common.CatalogItemStatus;
import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.factory.BrandFactory;
import com.nona.domain.catalog.repo.BrandRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 平台端品牌用例：品牌库维护编排。
 * <p>
 * 品牌为 global 数据（无租户隔离、无跨店语义），用例不感知租户。
 * 写路径（创建/更新/禁用/启用）在用例事务内完成「守卫 → 领域操作 → 落库」；
 * 「删除」= 禁用（disable-not-delete 软删），与显式禁用同语义（行保留）。
 * 名称唯一性守卫（含禁用态不可复用）：创建/更新前按名称查重，撞名 400；
 * 并发竞态由数据库唯一约束兜底（仓储层翻译为业务冲突）。
 * 禁用品牌既有商品保持可见、新商品不可挂（挂载校验在商品创建/更新路径）。
 *
 * @author nona9961
 */
@Service
public class BrandUseCase {

    /**
     * 品牌仓储
     */
    private final BrandRepository brandRepository;

    /**
     * 品牌工厂
     */
    private final BrandFactory brandFactory;

    /**
     * 商品仓储（禁用守卫：有商品引用的品牌拒绝禁用）
     */
    private final ProductRepository productRepository;

    /**
     * 构造品牌用例。
     *
     * @param brandRepository   品牌仓储
     * @param brandFactory      品牌工厂
     * @param productRepository 商品仓储（禁用前引用检查）
     */
    public BrandUseCase(BrandRepository brandRepository,
                        BrandFactory brandFactory,
                        ProductRepository productRepository) {
        this.brandRepository = brandRepository;
        this.brandFactory = brandFactory;
        this.productRepository = productRepository;
    }

    /**
     * 品牌列表（按创建序；状态过滤可选）。
     *
     * @param filter 状态过滤（契约枚举）；null 表示不过滤
     * @return 品牌条目列表
     */
    public List<BrandItem> list(CatalogItemStatus filter) {
        final List<Brand> brands = filter == null
                ? brandRepository.listAll()
                : brandRepository.listByStatus(toDomainStatus(filter));
        return brands.stream().map(BrandUseCase::toItem).toList();
    }

    /**
     * 新增品牌：名称唯一性守卫 → 工厂创建 → 落库。
     * 初始状态 ENABLED；logo 可空。
     *
     * @param request 品牌名称与 logo
     * @return 新建品牌
     */
    @Transactional
    public BrandItem create(BrandRequest request) {
        requireNameFree(request.name(), null);
        final Brand brand = brandFactory.create(request.name(), request.logo());
        brandRepository.save(brand);
        return toItem(brand);
    }

    /**
     * 更新品牌：目标必须存在（否则 404）；名称唯一性守卫（改回自身原名合法）；
     * 名称必填非空（聚合守卫兜底）；logo 可空（传 null 清除）。
     *
     * @param brandId 品牌 ID
     * @param request 新名称与新 logo
     * @return 更新后的品牌
     */
    @Transactional
    public BrandItem update(Long brandId, BrandRequest request) {
        final Brand brand = requireBrand(brandId);
        requireNameFree(request.name(), brandId);
        brand.rename(request.name());
        brand.updateLogo(request.logo());
        brandRepository.save(brand);
        return toItem(brand);
    }

    /**
     * 删除品牌（「删除」= 禁用软删）：目标必须存在（否则 404），
     * 状态迁移 DISABLED，行保留。
     *
     * @param brandId 品牌 ID
     */
    @CrossTenant
    @Transactional
    public void delete(Long brandId) {
        disable(brandId);
    }

    /**
     * 禁用品牌（与删除同语义，显式状态动作）：禁用前检查商品引用——有
     * 商品引用的品牌拒绝禁用（409，引用守卫；商品引用检查
     * 为跨租户读，故读放行 @CrossTenant——写门禁不受影响，仍由
     * elevatedInTransaction 语义管辖）。
     *
     * @param brandId 品牌 ID
     */
    @CrossTenant
    @Transactional
    public void disable(Long brandId) {
        final Brand brand = requireBrand(brandId);
        requireNoProductReference(brandId);
        brand.disable();
        brandRepository.save(brand);
    }

    /**
     * 启用品牌（禁用态恢复）。
     *
     * @param brandId 品牌 ID
     */
    @Transactional
    public void enable(Long brandId) {
        final Brand brand = requireBrand(brandId);
        brand.enable();
        brandRepository.save(brand);
    }

    /**
     * 商品引用守卫：存在引用本品牌的商品即拒绝禁用（409 冲突语义；
     * 引用解除路径 = 商品侧删引用/删商品，属商品域用例）。
     *
     * @param brandId 品牌 ID
     */
    private void requireNoProductReference(Long brandId) {
        if (productRepository.existsByBrandId(brandId)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_IN_USE.code(), "存在商品引用该品牌，不能禁用");
        }
    }

    /**
     * 名称唯一性守卫：目标名称已被其他品牌占用（含禁用态）即冲突 400。
     *
     * @param name      目标名称
     * @param excludeId 排除的品牌 ID（更新场景排除自身；创建传 null）
     */
    private void requireNameFree(String name, Long excludeId) {
        final Optional<Brand> existing = brandRepository.findByName(name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NAME_CONFLICT.code(), "品牌名称已存在");
        }
    }

    /**
     * 按 ID 加载品牌并断言存在（不存在按 404 呈现）。
     *
     * @param brandId 品牌 ID
     * @return 品牌聚合
     */
    private Brand requireBrand(Long brandId) {
        final Brand brand = brandRepository.getByID(brandId);
        if (brand == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NOT_FOUND.code(), "品牌不存在");
        }
        return brand;
    }

    /**
     * 契约状态 → 领域状态（枚举互转显式 switch，不依赖序遍历）。
     *
     * @param itemStatus 契约状态
     * @return 领域状态
     */
    private static BrandStatus toDomainStatus(CatalogItemStatus itemStatus) {
        return switch (itemStatus) {
            case ENABLED -> BrandStatus.ENABLED;
            case DISABLED -> BrandStatus.DISABLED;
        };
    }

    /**
     * 领域品牌 → 契约条目。
     *
     * @param brand 领域品牌
     * @return 契约条目
     */
    private static BrandItem toItem(Brand brand) {
        return new BrandItem(brand.getId(), brand.getName(), brand.getLogo(), brand.getStatus().name());
    }
}