package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.inf.persistence.po.catalog.BrandPO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 品牌 JPA 仓储（brand 表，global 无租户列）
 * 提供按状态查询与名称唯一性守卫辅助查询。
 * 采用完整 JPA 仓储接口（ListCrudRepository 超集）：saveAndFlush 用于并发
 * 撞名唯一约束冲突的即时捕获（延迟 flush 会使冲突越过仓储捕获点）。
 *
 * @author nona9961
 */
public interface BrandJpaRepository extends JpaRepository<BrandPO, Long> {

    /**
     * 全部行（按创建序，ID 升序，顺序稳定）。
     *
     * @return 品牌行；无数据为空列表
     */
    List<BrandPO> findAllByOrderByIdAsc();

    /**
     * 按状态查询（按创建序，ID 升序）。
     *
     * @param status 状态
     * @return 品牌行；无数据为空列表
     */
    List<BrandPO> findByStatusOrderByIdAsc(BrandStatus status);

    /**
     * 按名称查询（名称唯一约束保证至多一行）。
     *
     * @param name 品牌名称
     * @return 品牌行；不存在返回空
     */
    Optional<BrandPO> findByName(String name);
}