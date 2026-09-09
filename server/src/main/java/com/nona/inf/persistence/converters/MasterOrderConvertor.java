package com.nona.inf.persistence.converters;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主订单聚合根 ↔ 主订单 PO 转换器（主表 master_order，global；无
 * 从表——子单 id 集合经 sub_order 反查，以 other 传入）。
 * <p>
 * 主表行 = 聚合根身份（id = 主订单主键，orderNo 唯一业务单号，buyerId
 * 业务关联列）；地址六列（recipient/phone/province/city/district/
 * detail）+ 金额四列（goodsAmount/freightAmount/discount/paidAmount）
 * 为 AddressSnapshot/AmountDetail 不可变 VO 的扁平化快照列（B7.6
 * 创建时定型）；status 为派生态（子单投影经 MasterOrderStatusDeriver
 * 刷新后由聚合状态读入）。转换器不作为装载端校验承担者——快照形态与
 * 金额恒等式守卫收敛在领域构造路径。
 *
 * @author nona9961
 */
@Component
public class MasterOrderConvertor
        extends AbstractConvertor<MasterOrder, MasterOrderPO, List<Long>> {

    /**
     * {@inheritDoc}
     * <p>
     * 字段逐列映射 + AddressSnapshot 六列回收 + AmountDetail 四列
     * 回收；other = 子单引用 id 集合（按 sub_order.master_order_id
     * 反查，null 按空列表处理——领域构造器以 ORDER_SUB_EMPTY 守卫
     * 空集合脏数据）。
     */
    @Override
    protected MasterOrderPO safedConvertToPO(MasterOrder root) {
        final MasterOrderPO po = new MasterOrderPO();
        po.setId(root.getId());
        po.setOrderNo(root.getOrderNo());
        po.setBuyerId(root.getBuyerId());
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
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected MasterOrder safedConvertToRoot(MasterOrderPO po, List<Long> subOrderIds) {
        final List<Long> ids = subOrderIds == null ? List.of() : subOrderIds;
        return new MasterOrder(po.getId(), po.getOrderNo(), po.getBuyerId(),
                new AddressSnapshot(po.getRecipient(), po.getPhone(), po.getProvince(),
                        po.getCity(), po.getDistrict(), po.getDetail()),
                new AmountDetail(po.getGoodsAmount(), po.getFreightAmount(),
                        po.getDiscount(), po.getPaidAmount()),
                ids, List.of(), po.getStatus(), po.getCreateTime());
    }
}