package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 运费模板 JPA 仓储（freight_template 表，模板聚合根根行，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺行——跨店铺访问在
 * 数据访问层即被拦截（fail-closed）；店铺维度查询（shop_id）与租户
 * 过滤共同定位行集（同一店铺下租户列与 shop_id 一致）。
 *
 * @author nona9961
 */
public interface FreightTemplateJpaRepository extends ListCrudRepository<FreightTemplatePO, Long> {

    /**
     * 按所属店铺查询全部模板（新模板在前——ID 倒序）。
     *
     * @param shopId 店铺 ID
     * @return 模板列表；无模板返回空列表
     */
    List<FreightTemplatePO> findByShopIdOrderByIdDesc(Long shopId);
}