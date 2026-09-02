package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 平台分类 JPA 仓储（platform_category 表，global 无租户列）
 * 提供按状态/排序查询与名称唯一性守卫辅助查询。
 * 采用完整 JPA 仓储接口（ListCrudRepository 超集）：saveAndFlush 用于并发
 * 撞名唯一约束冲突的即时捕获（延迟 flush 会使冲突越过仓储捕获点）。
 *
 * @author nona9961
 */
public interface PlatformCategoryJpaRepository extends JpaRepository<PlatformCategoryPO, Long> {

    /**
     * 全部行（按展示排序升序，同序按 ID 升序，展示顺序稳定）。
     *
     * @return 分类行；无数据为空列表
     */
    List<PlatformCategoryPO> findAllByOrderByOrderNoAscIdAsc();

    /**
     * 按状态查询（按展示排序升序，同序按 ID 升序）。
     *
     * @param status 状态
     * @return 分类行；无数据为空列表
     */
    List<PlatformCategoryPO> findByStatusOrderByOrderNoAscIdAsc(CategoryStatus status);

    /**
     * 按名称查询（名称唯一约束保证至多一行）。
     *
     * @param name 分类名称
     * @return 分类行；不存在返回空
     */
    Optional<PlatformCategoryPO> findByName(String name);

    /**
     * 当前最大展示排序（空表返回 0）。
     *
     * @return 最大排序值
     */
    @Query("select coalesce(max(c.orderNo), 0) from PlatformCategoryPO c")
    int findMaxOrderNo();
}