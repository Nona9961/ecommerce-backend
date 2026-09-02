package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.Shop;
import com.nona.persistence.BaseRepository;

/**
 * 店铺仓储接口（Shop 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 简单聚合仓储：按店铺 ID 加载/保存/删除（主表 shop + 从表 shop_category
 * 集合经变更追踪驱动落库）。Shop 为租户锚点（持 global 主表，不自引用
 * tenant 列），店铺分类从表为 tenant-scoped（tenant=shopId）——跨店铺
 * 访问分类在加载路径即被租户过滤拦截（fail-closed）。
 *
 * @author nona9961
 */
public interface ShopRepository extends BaseRepository<Long, Shop> {
}