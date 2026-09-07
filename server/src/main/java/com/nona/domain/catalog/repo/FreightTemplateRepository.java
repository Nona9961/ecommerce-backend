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

    /**
     * 按店铺定位默认运费模板（is_default=true 行；租户过滤 fail-closed）：
     * 回退锚点查询——商品未绑定/绑定悬挂时详情读/试算回退目标（非空设计
     * §2.5：回退值本身是显式实体行）。
     *
     * @param shopId 店铺 ID
     * @return 默认模板；行缺失返回 null（消费侧防御拒绝
     *         {@code catalog.freight_default_template_not_found}，不静默降级）
     */
    FreightTemplate findDefaultByShopId(Long shopId);
}