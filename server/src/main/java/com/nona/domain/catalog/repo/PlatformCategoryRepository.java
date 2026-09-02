package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.persistence.BaseRepository;

import java.util.List;
import java.util.Optional;

/**
 * 平台分类仓储接口（PlatformCategory 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 简单聚合仓储：平台分类为 global 数据（不随租户隔离，平台货架组织单位），
 * 按 ID 加载/保存；「删除」= 禁用软删（disable-not-delete），域内删除路径
 * 走状态迁移（disable），物理删行仅作脚手架契约保留（域流程不调用）。
 * 名称全局唯一（数据库唯一约束 uk_platform_category_name +
 * 用例层守卫）；列表按展示排序升序（order, id）。
 *
 * @author nona9961
 */
public interface PlatformCategoryRepository extends BaseRepository<Long, PlatformCategory> {

    /**
     * 按名称查询分类（名称唯一性守卫用；至多一行由唯一约束保证）。
     *
     * @param name 分类名称
     * @return 分类；不存在返回空
     */
    Optional<PlatformCategory> findByName(String name);

    /**
     * 全部分类（按展示排序升序，同序按 ID 升序，展示顺序稳定）。
     *
     * @return 分类列表；无数据为空列表
     */
    List<PlatformCategory> listAll();

    /**
     * 按状态查询分类（按展示排序升序，同序按 ID 升序）。
     *
     * @param status 状态过滤
     * @return 分类列表；无数据为空列表
     */
    List<PlatformCategory> listByStatus(CategoryStatus status);

    /**
     * 当前最大展示排序（空表返回 0；新分类自动排序 = 返回值 + 1）。
     *
     * @return 最大排序值
     */
    int findMaxOrder();
}