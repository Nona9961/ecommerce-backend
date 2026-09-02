package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 运费模板仓储接口（FreightTemplate 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 简单聚合仓储：按模板 ID 加载/保存/删除（主表 freight_template 单表单行，
 * tenant=shopId——跨店铺访问在主键路径即被租户过滤拦截，fail-closed）。
 * 列表按店铺查询（租户过滤 + shop_id 双重定位，新模板在前）；
 * 列表返回的聚合对象仅供读取（未登记变更追踪，修改后不可直接 save）。
 *
 * @author nona9961
 */
public interface FreightTemplateRepository extends BaseRepository<Long, FreightTemplate> {

    /**
     * 按店铺列出运费模板（新模板在前）。
     *
     * @param shopId 店铺 ID
     * @return 模板列表；无模板返回空列表
     */
    List<FreightTemplate> listByShopId(Long shopId);
}