package com.nona.domain.order.entity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主单状态派生器（order 域纯函数）：主单整体状态 = 全部子单状态的聚合
 * 派生——下单/支付/发货/完成/退款/超时编排（应用层用例）在事务内推进
 * 子单状态后，把子单状态投影列表传入本函数重算主单整体状态。
 * <p>
 * 派生规则（钉死，测试矩阵全覆盖）：
 * <ol>
 *     <li>输入非法拒绝：空列表 / null 拒绝（主单至少一个子单，空输入
 *         为装配错误）；含 {@link SubOrderStatus#PENDING_PAYMENT} 且非
 *         全部拒绝（不可达组合——下单/支付/取消均为主单整单语义，
 *         TD-10 整单支付，待支付不可能与其它状态并存）；</li>
 *     <li>旁路覆盖：全部 {@code CANCELLED} → 主单已取消；全部
 *         {@code CLOSED} → 主单已关闭；全部 {@code REFUNDED} →
 *         主单已退款；任一 {@code REFUNDING} → 主单退款中（在途资金
 *         显性优先）；</li>
 *     <li>主链派生：剔除旁路终态（cancelled/closed/refunded）后——
 *         全部已完成 → 已完成；全部已发货 → 已发货；任一已发货/已完成
 *         → 部分发货；全部已支付 → 已支付；全部待支付 → 待支付；</li>
 *     <li>旁路混合兜底：剔除旁路终态后为空（旁路与旁路混合，如部分子单
 *         关闭 + 部分子单退款成功）→ 任一已退款 → 已退款（「钱已退」
 *         优先展示），否则任一已关闭 → 已关闭；旁路终态与主链混合
 *         （剔除旁路后非空，如部分子单退款 + 其余已发货/已支付）→ 按
 *         剔除后剩余主链推进度最高状态映射（已完成 &gt; 已发货 &gt; 已支付，
 *         如已退款 + 已发货 + 已支付 → 已发货）——已退/已关/已取消子单
 *         不再参与履约推进，剩余部分按最高推进展示；</li>
 *     <li>其余输入组合按规则不可能（防御性拒绝，抛
 *         {@link IllegalArgumentException}——纯函数输入域外，属装配
 *         错误而非业务拒绝；正常路径不可达）。</li>
 * </ol>
 * 本类为纯函数载体（无状态、无私货），实例不可创建；主单聚合方法
 * {@link MasterOrder#deriveStatus} 委托本函数刷新整体状态。
 *
 * @author nona9961
 */
public class MasterOrderStatusDeriver {

    /**
     * 私有构造器，禁止实例化。
     */
    private MasterOrderStatusDeriver() {
    }

    /**
     * 按子单状态集合派生主单整体状态（规则见类注释，测试矩阵钉死）。
     *
     * @param subOrderStatuses 子单状态投影列表（顺序无关，去重由实现保证；
     *                         非空且不可含「待支付混合态」）
     * @return 派生的主单整体状态
     * @throws IllegalArgumentException 空/null 输入或不可达组合（装配错误防御）
     */
    public static MasterOrderStatus derive(List<SubOrderStatus> subOrderStatuses) {
        if (subOrderStatuses == null || subOrderStatuses.isEmpty()) {
            throw new IllegalArgumentException("子单状态投影不能为空（主单至少包含一个子单）");
        }
        final Set<SubOrderStatus> distinct = new LinkedHashSet<>(subOrderStatuses);
        if (distinct.contains(SubOrderStatus.PENDING_PAYMENT) && distinct.size() > 1) {
            throw new IllegalArgumentException("待支付与其它状态并存的投影组合不可达（整单支付语义）");
        }
        if (distinct.contains(SubOrderStatus.REFUNDING)) {
            return MasterOrderStatus.REFUNDING;
        }
        final Set<SubOrderStatus> remaining = new LinkedHashSet<>(distinct);
        remaining.remove(SubOrderStatus.CANCELLED);
        remaining.remove(SubOrderStatus.CLOSED);
        remaining.remove(SubOrderStatus.REFUNDED);
        if (remaining.isEmpty()) {
            if (distinct.contains(SubOrderStatus.REFUNDED)) {
                return MasterOrderStatus.REFUNDED;
            }
            if (distinct.contains(SubOrderStatus.CLOSED)) {
                return MasterOrderStatus.CLOSED;
            }
            return MasterOrderStatus.CANCELLED;
        }
        if (remaining.size() < distinct.size()) {
            if (remaining.contains(SubOrderStatus.COMPLETED)) {
                return MasterOrderStatus.COMPLETED;
            }
            if (remaining.contains(SubOrderStatus.SHIPPED)) {
                return MasterOrderStatus.SHIPPED;
            }
            if (remaining.contains(SubOrderStatus.PAID)) {
                return MasterOrderStatus.PAID;
            }
            return MasterOrderStatus.PENDING_PAYMENT;
        }
        if (remaining.size() == 1) {
            final SubOrderStatus single = remaining.iterator().next();
            if (single == SubOrderStatus.COMPLETED) {
                return MasterOrderStatus.COMPLETED;
            }
            if (single == SubOrderStatus.SHIPPED) {
                return MasterOrderStatus.SHIPPED;
            }
            if (single == SubOrderStatus.PAID) {
                return MasterOrderStatus.PAID;
            }
            return MasterOrderStatus.PENDING_PAYMENT;
        }
        return MasterOrderStatus.PARTIALLY_SHIPPED;
    }
}