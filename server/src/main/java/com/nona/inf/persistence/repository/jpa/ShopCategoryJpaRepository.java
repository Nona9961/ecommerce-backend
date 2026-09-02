package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 店铺分类 JPA 仓储（shop_category 表，店铺聚合从表行集合，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺行——跨店铺访问在
 * 数据访问层即被拦截（fail-closed）；店铺维度查询（shop_id）与租户
 * 过滤共同定位行集（同一店铺下租户列与 shop_id 一致）。
 *
 * @author nona9961
 */
public interface ShopCategoryJpaRepository extends ListCrudRepository<ShopCategoryPO, Long> {

    /**
     * 按所属店铺查询全部分类（按排序升序，展示顺序稳定）。
     *
     * @param shopId 店铺 ID
     * @return 分类列表；无分类返回空列表
     */
    List<ShopCategoryPO> findByShopIdOrderByOrderNoAscIdAsc(Long shopId);

    /**
     * 按所属店铺删除全部分类（deleteByID 级联删除用）。
     *
     * @param shopId 店铺 ID
     * @return 删除的行数
     */
    long deleteByShopId(Long shopId);
}