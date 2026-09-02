package com.nona.application.admin;

import com.nona.api.admin.PlatformCategoryItem;
import com.nona.api.admin.PlatformCategoryRequest;
import com.nona.api.common.CatalogItemStatus;
import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.domain.catalog.factory.PlatformCategoryFactory;
import com.nona.domain.catalog.repo.PlatformCategoryRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 平台端平台分类用例：一级分类维护编排。
 * <p>
 * 平台分类为 global 数据（无租户隔离、无跨店语义），用例不感知租户。
 * 写路径（创建/更新/禁用/启用）在用例事务内完成「守卫 → 领域操作 → 落库」；
 * 「删除」= 禁用（disable-not-delete 软删），与显式禁用同语义（行保留）。
 * 名称唯一性守卫（含禁用态不可复用）：创建/更新前按名称查重，撞名 400；
 * 并发竞态由数据库唯一约束兜底（仓储层翻译为业务冲突）。
 * 排序赋值：请求显式正数则原样采用，否则自动取当前最大排序 + 1。
 *
 * @author nona9961
 */
@Service
public class PlatformCategoryUseCase {

    /**
     * 平台分类仓储
     */
    private final PlatformCategoryRepository categoryRepository;

    /**
     * 平台分类工厂
     */
    private final PlatformCategoryFactory categoryFactory;

    /**
     * 构造平台分类用例。
     *
     * @param categoryRepository 平台分类仓储
     * @param categoryFactory    平台分类工厂
     */
    public PlatformCategoryUseCase(PlatformCategoryRepository categoryRepository,
                                   PlatformCategoryFactory categoryFactory) {
        this.categoryRepository = categoryRepository;
        this.categoryFactory = categoryFactory;
    }

    /**
     * 分类列表（按展示排序升序；状态过滤可选）。
     *
     * @param filter 状态过滤（契约枚举）；null 表示不过滤
     * @return 分类条目列表
     */
    public List<PlatformCategoryItem> list(CatalogItemStatus filter) {
        final List<PlatformCategory> categories = filter == null
                ? categoryRepository.listAll()
                : categoryRepository.listByStatus(toDomainStatus(filter));
        return categories.stream().map(PlatformCategoryUseCase::toItem).toList();
    }

    /**
     * 新增分类：名称唯一性守卫 → 排序赋值（显式正数 / 自动最大 + 1）→
     * 工厂创建 → 落库。初始状态 ENABLED。
     *
     * @param request 分类名称与排序
     * @return 新建分类
     */
    @Transactional
    public PlatformCategoryItem create(PlatformCategoryRequest request) {
        requireNameFree(request.name(), null);
        final int order = (request.order() != null && request.order() > 0)
                ? request.order()
                : categoryRepository.findMaxOrder() + 1;
        final PlatformCategory category = categoryFactory.create(request.name(), order);
        categoryRepository.save(category);
        return toItem(category);
    }

    /**
     * 更新分类：目标必须存在（否则 404）；名称唯一性守卫（改回自身原名合法）；
     * 名称必填非空（聚合守卫兜底）；排序显式正数时更新，空缺保持。
     *
     * @param categoryId 分类 ID
     * @param request    新名称与新排序
     * @return 更新后的分类
     */
    @Transactional
    public PlatformCategoryItem update(Long categoryId, PlatformCategoryRequest request) {
        final PlatformCategory category = requireCategory(categoryId);
        requireNameFree(request.name(), categoryId);
        category.rename(request.name());
        if (request.order() != null && request.order() > 0) {
            category.reorder(request.order());
        }
        categoryRepository.save(category);
        return toItem(category);
    }

    /**
     * 删除分类（「删除」= 禁用软删）：目标必须存在（否则 404），
     * 状态迁移 DISABLED，行保留。
     *
     * @param categoryId 分类 ID
     */
    @Transactional
    public void delete(Long categoryId) {
        disable(categoryId);
    }

    /**
     * 禁用分类（与删除同语义，显式状态动作）。
     *
     * @param categoryId 分类 ID
     */
    @Transactional
    public void disable(Long categoryId) {
        final PlatformCategory category = requireCategory(categoryId);
        category.disable();
        categoryRepository.save(category);
    }

    /**
     * 启用分类（禁用态恢复）。
     *
     * @param categoryId 分类 ID
     */
    @Transactional
    public void enable(Long categoryId) {
        final PlatformCategory category = requireCategory(categoryId);
        category.enable();
        categoryRepository.save(category);
    }

    /**
     * 名称唯一性守卫：目标名称已被其他分类占用（含禁用态）即冲突 400。
     *
     * @param name      目标名称
     * @param excludeId 排除的分类 ID（更新场景排除自身；创建传 null）
     */
    private void requireNameFree(String name, Long excludeId) {
        final Optional<PlatformCategory> existing = categoryRepository.findByName(name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NAME_CONFLICT.code(), "分类名称已存在");
        }
    }

    /**
     * 按 ID 加载分类并断言存在（不存在按 404 呈现）。
     *
     * @param categoryId 分类 ID
     * @return 分类聚合
     */
    private PlatformCategory requireCategory(Long categoryId) {
        final PlatformCategory category = categoryRepository.getByID(categoryId);
        if (category == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NOT_FOUND.code(), "平台分类不存在");
        }
        return category;
    }

    /**
     * 契约状态 → 领域状态（枚举互转显式 switch，不依赖序遍历）。
     *
     * @param itemStatus 契约状态
     * @return 领域状态
     */
    private static CategoryStatus toDomainStatus(CatalogItemStatus itemStatus) {
        return switch (itemStatus) {
            case ENABLED -> CategoryStatus.ENABLED;
            case DISABLED -> CategoryStatus.DISABLED;
        };
    }

    /**
     * 领域分类 → 契约条目。
     *
     * @param category 领域分类
     * @return 契约条目
     */
    private static PlatformCategoryItem toItem(PlatformCategory category) {
        return new PlatformCategoryItem(
                category.getId(), category.getName(), category.getOrder(), category.getStatus().name());
    }
}