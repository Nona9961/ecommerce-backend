package com.nona.domain.order.factory;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子订单聚合根工厂：子单的创建入口（ID 生成 + 装配形态校验）。
 * <p>
 * 创建语义（TD-10 拆单）：下单编排按店铺分组逐店创建子单（每店一个子
 * 单，N 店铺 → N 子单；跨店任一 SKU 预占失败整单回滚的编排归下单规
 * 划）；归属店铺（tenant=shopId）与归属主单（masterOrderId 引用值，
 * 主单对象尚未创建——先子后主两阶段装配）创建时定型。
 * <p>
 * 校验分层：工厂只校验装配形态（必填/非空/条目非空）；金额自洽
 * （商品总额 == Σ 条目小计）与状态机迁移守卫收敛在聚合构造与聚合方法；
 * 运费金额来源为跨上下文（WU-13 计算器经用例层读入）——域不感知
 * catalog 端口。
 *
 * @author nona9961
 */
@Component
public class SubOrderFactory {

    /**
     * 创建子订单（状态定型为待支付、运单引用为空）。
     *
     * @param masterOrderId 归属主订单 ID（必填——下单编排预生成的引用值）
     * @param shopId        归属店铺 ID（必填，tenant=shopId）
     * @param subOrderNo    子订单号（必填非空，TD-13 业务单号）
     * @param address       收货地址快照（必填）
     * @param amount        金额明细（必填，商品总额须与订单项合计自洽）
     * @param items         订单项快照集合（必填非空，至少一项）
     * @return 新建子订单（待支付，待仓储保存）
     */
    public SubOrder createSubOrder(Long masterOrderId, Long shopId, String subOrderNo,
                                   AddressSnapshot address, AmountDetail amount,
                                   List<OrderItem> items) {
        BusinessAssert.assertNonNull(masterOrderId, "子订单归属主订单 ID 不能为空");
        BusinessAssert.assertNonNull(shopId, "子订单归属店铺 ID 不能为空");
        BusinessAssert.assertTrue(subOrderNo != null && !subOrderNo.isBlank(),
                "子订单号不能为空");
        BusinessAssert.assertNonNull(address, "子订单地址快照不能为空");
        BusinessAssert.assertNonNull(amount, "子订单金额明细不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SUB_EMPTY.code(),
                items != null && !items.isEmpty(), "子订单必须包含至少一个订单项");
        return new SubOrder(IDUtils.generateID(), masterOrderId, shopId, subOrderNo,
                address, amount, items);
    }
}