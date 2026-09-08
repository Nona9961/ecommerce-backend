package com.nona.domain.logistics.ports;

import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.order.entity.SubOrderStatus;

import java.time.Instant;

/**
 * 平台物流视图行（服务层组装后的视图语义；P5.1 ① 跨店铺订单物流列表
 * ③ 超时未发货标记）。
 * <p>
 * 行单元 = 子单：子单自有字段（子单 ID/单号/主单 ID/店铺/履约状态）+
 * 物流凭据附注（运单 ID/承运/单号/物流状态——未发货子单为空）+ 超时
 * 标记。
 * <p>
 * 超时未发货标记判定公式（收敛在服务实现行映射处，钉死语义）：
 * <pre>
 * timeoutOverdue = (subOrderStatus == PAID)
 *                  &amp;&amp; (timeoutAt != null)
 *                  &amp;&amp; (timeoutAt 不晚于判定时刻)
 * </pre>
 * 语义论证：
 * <ul>
 *     <li><b>状态条件 PAID</b>：发货超时针对「已支付子单 X 天未发货」，
 *         已发货（SHIPPED）/已关闭（CLOSED）等行不标记——已发货子单
 *         即使残留截止时间列也不构成「未发货」；</li>
 *     <li><b>截止时间列</b>：{@code timeout_at} 为业务表冗余截止时间列
 *         （发布/清除路径由履约推进与超时引擎承载，本视图只读消费），
 *         PAID 行存在该列即发货超时 deadline 在位（状态-类型不变量：
 *         PAID 履约态只可能注册发货超时类型 deadline，故判定不依赖
 *         类型列）；</li>
 *     <li><b>认领位不参与</b>：认领位（claimed）是调度处理中的暂态
 *         标记（成功即清除、失败即随事务回滚归零，不存在滞留），与
 *         候选扫描同口径，不进入判定；</li>
 *     <li><b>判定时刻</b>：服务实现行映射时取当前时刻（UTC 语义，
 *         与截止时间列的 UTC 墙钟语义一致）；判定为行级内存计算，
 *         单页至多百行无性能面。</li>
 * </ul>
 *
 * @param subOrderId    子单 ID（行单元主键）
 * @param subOrderNo    子单号（业务单号，运营定位凭据）
 * @param masterOrderId 归属主单 ID（跨店列表区分同买家多店订单）
 * @param shopId        归属店铺 ID（跨店铺全集行的店铺维度）
 * @param shopName      店铺名（展示冗余，镜像表 JOIN 投影）
 * @param subOrderStatus 子单履约状态（行主维度状态）
 * @param waybillId     运单 ID（未发货子单为空）
 * @param company       承运公司（未发货子单为空）
 * @param trackingNo    运单号（未发货子单为空）
 * @param waybillStatus 运单物流状态（未发货子单为空）
 * @param timeoutAt     发货超时截止时间（无 deadline 记录为空；
 *                      原始列透传，供展示超时时刻）
 * @param timeoutOverdue 超时未发货标记（判定公式见类注释）
 */
public record PlatformLogisticsViewItem(Long subOrderId, String subOrderNo,
                                        Long masterOrderId, Long shopId, String shopName,
                                        SubOrderStatus subOrderStatus,
                                        Long waybillId, String company, String trackingNo,
                                        WaybillStatus waybillStatus,
                                        Instant timeoutAt, boolean timeoutOverdue) {
}