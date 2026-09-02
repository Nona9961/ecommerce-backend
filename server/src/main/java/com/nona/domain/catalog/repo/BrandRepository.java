package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.persistence.BaseRepository;

import java.util.List;
import java.util.Optional;

/**
 * 品牌仓储接口（Brand 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 简单聚合仓储：品牌为 global 数据（不随租户隔离，平台品牌库），
 * 按 ID 加载/保存；「删除」= 禁用软删（disable-not-delete），域内删除路径
 * 走状态迁移（disable），物理删行仅作脚手架契约保留（域流程不调用）。
 * 名称全局唯一（数据库唯一约束 uk_brand_name + 用例层守卫）；
 * 列表按创建序（ID 升序）。
 *
 * @author nona9961
 */
public interface BrandRepository extends BaseRepository<Long, Brand> {

    /**
     * 按名称查询品牌（名称唯一性守卫用；至多一行由唯一约束保证）。
     *
     * @param name 品牌名称
     * @return 品牌；不存在返回空
     */
    Optional<Brand> findByName(String name);

    /**
     * 全部品牌（按创建序，ID 升序，顺序稳定）。
     *
     * @return 品牌列表；无数据为空列表
     */
    List<Brand> listAll();

    /**
     * 按状态查询品牌（按创建序，ID 升序）。
     *
     * @param status 状态过滤
     * @return 品牌列表；无数据为空列表
     */
    List<Brand> listByStatus(BrandStatus status);
}