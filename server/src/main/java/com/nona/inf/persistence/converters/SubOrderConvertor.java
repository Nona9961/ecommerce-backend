package com.nona.inf.persistence.converters;

import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子订单聚合根 ↔ 子订单 PO 转换器（主表 sub_order，tenant=shopId +
 * 从表 order_item 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 子订单主键，tenant_id 隔离列由 Hibernate
 * 租户机制自动注入、shop_id 业务关联列冗余，tenant=shopId 一致性由
 * 仓储/编排装配面保证）；地址六列 + 金额四列为快照扁平列；status 履约
 * 状态（迁移经聚合方法）；waybill_id 跨域引用（可空）。other 参数为
 * 从表行集合（读路径由仓储 getOther 提供，经 OrderItemConvertor
 * 逐行转换恢复 items）。超时 SQL 面三列（timeout_at/timeout_type/
 * claimed）为仓储实现侧维护，转换器不读写。
 *
 * @author nona9961
 */
@Component
public class SubOrderConvertor
        extends AbstractConvertor<SubOrder, SubOrderPO, List<OrderItemPO>> {

    /**
     * 条目行转换器
     */
    private final OrderItemConvertor orderItemConvertor;

    /**
     * 构造子订单转换器。
     *
     * @param orderItemConvertor 条目行转换器
     */
    public SubOrderConvertor(OrderItemConvertor orderItemConvertor) {
        this.orderItemConvertor = orderItemConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 字段逐列映射（含地址/金额快照扁平化）；超时三列
     * （timeoutAt/timeoutType/claimed）不触碰——由仓储实现侧维护
     * （D11，领域对象无对应字段）。
     */
    @Override
    protected SubOrderPO safedConvertToPO(SubOrder root) {
        final SubOrderPO po = new SubOrderPO();
        po.setId(root.getId());
        po.setMasterOrderId(root.getMasterOrderId());
        po.setShopId(root.getShopId());
        po.setSubOrderNo(root.getSubOrderNo());
        po.setRecipient(root.getAddress().getRecipient());
        po.setPhone(root.getAddress().getPhone());
        po.setProvince(root.getAddress().getProvince());
        po.setCity(root.getAddress().getCity());
        po.setDistrict(root.getAddress().getDistrict());
        po.setDetail(root.getAddress().getDetail());
        po.setGoodsAmount(root.getAmount().getGoodsAmount());
        po.setFreightAmount(root.getAmount().getFreightAmount());
        po.setDiscount(root.getAmount().getDiscount());
        po.setPaidAmount(root.getAmount().getPaidAmount());
        po.setStatus(root.getStatus());
        po.setWaybillId(root.getWaybillId());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经 {@link OrderItemConvertor} 转换并经装载构造器恢复
     * 聚合（顺序保持加载序；集合空或 null 时由领域构造器以
     * ORDER_SUB_EMPTY 守卫拒绝脏数据）。
     */
    @Override
    protected SubOrder safedConvertToRoot(SubOrderPO po, List<OrderItemPO> itemPOs) {
        final List<OrderItemPO> rows = itemPOs == null ? List.of() : itemPOs;
        return new SubOrder(po.getId(), po.getMasterOrderId(), po.getShopId(),
                po.getSubOrderNo(),
                new AddressSnapshot(po.getRecipient(), po.getPhone(), po.getProvince(),
                        po.getCity(), po.getDistrict(), po.getDetail()),
                new AmountDetail(po.getGoodsAmount(), po.getFreightAmount(),
                        po.getDiscount(), po.getPaidAmount()),
                rows.stream().map(orderItemConvertor::toDomain).toList(),
                po.getStatus(), po.getWaybillId());
    }
}