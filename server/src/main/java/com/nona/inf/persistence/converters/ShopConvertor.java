package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Shop;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 店铺聚合根 ↔ 店铺 PO 转换器（主表 shop + 从表 shop_category 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 店铺 ID，租户锚点）；从表行经
 * {@link ShopCategoryConvertor} 逐行转换后由聚合方法装载（add 保持
 * 持久化排序值，不重排）。other 参数为从表行集合（读路径由仓储 getOther
 * 提供，经租户过滤仅含本店铺行）。
 *
 * @author nona9961
 */
@Component
public class ShopConvertor extends AbstractConvertor<Shop, ShopPO, List<ShopCategoryPO>> {

    /**
     * 店铺分类行转换器
     */
    private final ShopCategoryConvertor shopCategoryConvertor;

    /**
     * 构造店铺转换器。
     *
     * @param shopCategoryConvertor 店铺分类行转换器
     */
    public ShopConvertor(ShopCategoryConvertor shopCategoryConvertor) {
        this.shopCategoryConvertor = shopCategoryConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主表行承载聚合根身份（id = 店铺 ID）与店铺信息/状态，
     * 审计时间戳由 JPA auditing 填充（本表 global，无租户列）。
     */
    @Override
    protected ShopPO safedConvertToPO(Shop root) {
        final ShopPO po = new ShopPO();
        po.setId(root.getId());
        po.setName(root.getName());
        po.setLogo(root.getLogo());
        po.setDescription(root.getDescription());
        po.setStatus(root.getStatus());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经 {@link ShopCategoryConvertor} 转换并装载
     * （排序值原样保留，加载序无关紧要）。
     */
    @Override
    protected Shop safedConvertToRoot(ShopPO po, List<ShopCategoryPO> categoryPOs) {
        final Shop shop = new Shop(po.getId(), po.getName(), po.getLogo(), po.getDescription(), po.getStatus());
        for (final ShopCategoryPO categoryPO : categoryPOs) {
            shop.addCategory(shopCategoryConvertor.toDomain(categoryPO));
        }
        return shop;
    }
}