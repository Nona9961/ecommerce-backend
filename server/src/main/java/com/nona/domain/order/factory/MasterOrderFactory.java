package com.nona.domain.order.factory;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主订单聚合根工厂：主单的创建入口（ID 生成 + 金额恒等式装配校验）。
 * <p>
 * 创建语义（TD-10 拆单）：下单编排先逐店创建子单（子单工厂），收集子单
 * ID 集合与各子单金额投影，再创建主单——金额摘要（商品额/运费/优惠/
 * 实付）由编排按子单四维合计装配，本工厂以恒等式复核（不一致拒绝，
 * 守卫收敛在聚合构造路径）；子单引用集合创建后定型（跨聚合只存引用
 * ID）。订单号（TD-13：ORD + 日期 + snowflake 后段）由下单编排生成
 * 传入（生成逻辑收敛在编排侧一处），工厂仅校验形态。
 *
 * @author nona9961
 */
@Component
public class MasterOrderFactory {

    /**
     * 创建主订单（整体状态定型为待支付）。
     *
     * @param orderNo     订单号（必填非空，TD-13 业务单号）
     * @param buyerId     归属买家账号 ID（必填）
     * @param address     地址快照（必填，下单时固化）
     * @param amount      金额摘要（必填，须与子单金额投影四维合计自洽）
     * @param subOrderIds 子单引用 ID 集合（必填非空，拆单结果）
     * @param subAmounts  子单金额投影列表（必填，与 subOrderIds 等长
     *                      ——校验用：四维合计与金额摘要一致）
     * @return 新建主订单（待支付，待仓储保存）
     */
    public MasterOrder createMasterOrder(String orderNo, Long buyerId,
                                         AddressSnapshot address, AmountDetail amount,
                                         List<Long> subOrderIds, List<AmountDetail> subAmounts) {
        BusinessAssert.assertTrue(orderNo != null && !orderNo.isBlank(), "订单号不能为空");
        BusinessAssert.assertNonNull(buyerId, "归属买家账号 ID 不能为空");
        BusinessAssert.assertNonNull(address, "主订单地址快照不能为空");
        BusinessAssert.assertNonNull(amount, "主订单金额摘要不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SUB_EMPTY.code(),
                subOrderIds != null && !subOrderIds.isEmpty(), "主订单必须包含至少一个子订单");
        BusinessAssert.assertTrue(subAmounts != null && subAmounts.size() == subOrderIds.size(),
                "子单金额投影与子单引用集合必须等长");
        return new MasterOrder(IDUtils.generateID(), orderNo, buyerId, address, amount,
                subOrderIds, subAmounts);
    }
}