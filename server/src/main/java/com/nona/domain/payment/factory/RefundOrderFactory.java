package com.nona.domain.payment.factory;

import com.nona.domain.payment.entity.RefundOrder;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 退款单聚合根工厂：退款单的创建入口（ID 生成 + refundNo 生成 + 创建
 * 形态校验，生成逻辑收敛工厂一处——TD-13 单号规则 + 脚手架 IDUtils
 * 统一入口，PaymentOrderFactory 判例同构）。
 * <p>
 * 创建语义（绿阶段已实现：ID/refundNo 生成 + 创建定型 + 形态守卫，
 * 生成逻辑收敛工厂一处）：
 * <ol>
 *     <li><b>ID 生成</b>：聚合根主键经 {@code IDUtils.generateID()} 获取
 *         （Snowflake，refund_order 主表独立主键）；</li>
 *     <li><b>refundNo 生成</b>：TD-13 规则——{@code REF + yyyyMMdd +
 *         snowflake 后段}（如 REF2026090712345678）；refund_no 唯一约束
 *         兜底；</li>
 *     <li><b>形态守卫</b>：payNo 必填非空（关联支付单号，gateway.refund
 *         入参）、subOrderId 必填（操作单元，一子单一退款单锚点）、
 *         amount 必为正（退款金额 = 子单实付，B8.5③）、shippedAtApply
 *         必填（C9 回补判定快照）——守卫语义由绿阶段按本 javadoc 实现；</li>
 *     <li><b>定型</b>：status = PENDING、留痕集合 = 空集合（非空设计，
 *         无 null 语义）、channelRefundTxnNo = null（时间点语义：尚未
 *         受理成功）。</li>
 * </ol>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的仓储
 * 实现未落地，注册即装配错误；绿阶段接线时按既有先例补
 * {@code @Component} 并复验上下文）。
 *
 * @author nona9961
 */
@Component
public class RefundOrderFactory {

    /**
     * refundNo 日期段格式（TD-13：yyyyMMdd）。
     */
    private static final DateTimeFormatter REFUND_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 创建退款单（申请编排与发货超时编排的新建分支调用；防重判定在
     * 编排层按 sub_order_id 唯一约束语义查重）。
     *
     * @param payNo          关联支付单号（必填非空，原交易定位）
     * @param subOrderId     操作单元子单 ID（必填，一子单一退款单锚点）
     * @param amount         退款金额（分，= 子单实付，必为正）
     * @param shippedAtApply 申请时刻已发货快照（必填——未发货退款回补
     *                       可售（I7），已发货不回补（C9））
     * @param reason         申请原因（可空——买家不填原因时传 null）
     * @return 新建退款中退款单（未落库，待仓储保存）
     */
    public RefundOrder create(String payNo, Long subOrderId, long amount,
                              boolean shippedAtApply, String reason) {
        if (payNo == null || payNo.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code(),
                    "关联支付单号不能为空（原交易定位锚点）");
        }
        if (subOrderId == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code(),
                    "操作单元子单 ID 不能为空（一子单一退款单锚点）");
        }
        if (amount <= 0) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code(),
                    "退款金额必须为正（= 子单实付，B8.5③）");
        }
        final String refundNo = "REF" + LocalDate.now().format(REFUND_NO_DATE) + IDUtils.generateID();
        return new RefundOrder(IDUtils.generateID(), refundNo, payNo, subOrderId,
                amount, shippedAtApply, reason);
    }
}