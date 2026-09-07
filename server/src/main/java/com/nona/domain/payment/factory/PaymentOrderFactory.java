package com.nona.domain.payment.factory;

import com.nona.domain.payment.entity.PaymentOrder;

/**
 * 支付单聚合根工厂：支付单的创建入口（ID 生成 + payNo 生成 + 创建形态
 * 校验，生成逻辑收敛工厂一处——TD-13 单号规则 + 脚手架 IDUtils 统一
 * 入口）。
 * <p>
 * 创建语义（红阶段契约声明，实现留绿阶段——本类所有方法体均为
 * UnsupportedOperationException）：
 * <ol>
 *     <li><b>ID 生成</b>：聚合根主键经 {@code IDUtils.generateID()} 获取
 *         （Snowflake，payment_order 主表独立主键）；</li>
 *     <li><b>payNo 生成</b>：TD-13 规则——{@code PAY + yyyyMMdd +
 *         snowflake 后段}（如 PAY2026090712345678）；pay_no 唯一约束
 *         兜底；</li>
 *     <li><b>形态守卫</b>：orderId 必填非空、paidAmount 非负（支付金额
 *         = 主单实付分）、channel 必填非空（一期唯一实现 MOCK）、
 *         payTimeoutMillis 非负——守卫语义由绿阶段按本 javadoc 实现；</li>
 *     <li><b>定型</b>：status = PENDING_PAYMENT、留痕集合 = 空集合（非空
 *         设计，无 null 语义）、channelTxnNo = null（时间点语义：尚未
 *         回调）、timeoutAt = 当前时间 + payTimeoutMillis（B8.3 支付超时
 *         注册，(status, timeout_at) 复合索引扫描面）。</li>
 * </ol>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的仓储
 * 实现未落地，注册即装配错误；绿阶段接线时按既有先例补 {@code @Component}
 * 并复验上下文）。
 *
 * @author nona9961
 */
public class PaymentOrderFactory {

    /**
     * 创建待支付支付单（创建/复用判定的新建分支调用；复用分支在端口
     * 实现侧按 order_id 唯一约束语义查重）。
     *
     * @param orderId         关联主单 ID（必填非空，一对一锚点）
     * @param paidAmount      支付金额（分，= 主单实付；非负）
     * @param channel         支付渠道（必填非空；一期 MOCK）
     * @param payTimeoutMillis 支付超时时长（毫秒，规则值由调用方传入——
     *                         TimeoutType.ORDER_PAY 30 分钟）
     * @return 新建待支付支付单（未落库，待仓储保存）
     */
    public PaymentOrder create(Long orderId, long paidAmount, String channel,
                               long payTimeoutMillis) {
        throw new UnsupportedOperationException("红阶段契约：create 实现留绿阶段（PaymentOrderFactory：ID/payNo 生成 + 创建定型）");
    }
}