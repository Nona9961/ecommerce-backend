package com.nona.domain.logistics.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.ArrayList;
import java.util.List;

/**
 * 运单聚合根（waybill 主表行，global——租户中立，经 subOrderId 业务
 * 关联订单域子单，不建跨域外键）：子单发货的物流凭据——承运公司/运单
 * 号/状态/轨迹事件列表。
 * <p>
 * 持久化形态（契约声明）：waybill 主表（global，独立 Snowflake
 * 主键；subOrderId 业务关联列——在途唯一，重复发货拒绝的数据库防线；
 * company/trackingNo 非空列；status 状态列）+ waybill_track 从表（以
 * waybill_id（rootId）关联聚合根，track append-only——行追加不更新
 * 不删除）。变更落库适配 DifferRepository：读 → track 快照 → save →
 * 变更集驱动落库（主表行状态变更 + 从表条目追加）。
 * <p>
 * 关键不变量（全部收敛在本聚合内，包外无直接字段变更路径）：
 * <ol>
 *     <li>状态机单向相邻推进（PENDING_SHIPMENT → SHIPPED → IN_TRANSIT
 *         → DELIVERED）：跳级/重复/回退/终态推进均拒绝，收敛在
 *         {@link #advanceTo(WaybillTrack)}（logistics.status_illegal）；</li>
 *     <li>轨迹追加不可变：轨迹条目一经产生不可变更（WaybillTrack 全
 *         final 无变更路径），只经 advanceTo 追加；外部读面为不可变视图；</li>
 *     <li>轨迹时间线一致性：末条轨迹状态 == 运单当前状态（构造/装载
 *         守卫，自相矛盾的运单无法构造）；</li>
 *     <li>一子单一在途运单（重复发货拒绝）：语义由仓储契约
 *         {@code findInTransitBySubOrderId} 查询面 + {@link #isInTransit()}
 *         判定面承载，完整拒绝在发货编排（查得在途运单 → 抛
 *         logistics.sub_order_conflict）+ waybill.sub_order_id 在途唯一
 *         约束（数据库兜底）。</li>
 * </ol>
 * 创建必须经由 {@link com.nona.domain.logistics.factory.WaybillFactory}
 * （ID 生成 + 初始轨迹装配）；装载（仓储重建）走全量构造器（同一守卫
 * 面：形态不变量同样校验，防脏数据；不执行写路径校验——状态迁移守卫
 * 属业务写入校验，装载不适用）。
 *
 * @author nona9961
 */
public class Waybill {

    /**
     * 运单主键（Snowflake，waybill 主键，聚合根标识）
     */
    private final Long id;

    /**
     * 关联子单 ID（业务关联列，跨聚合引用 ID——不持子单对象；在途唯一
     * 锚点）
     */
    private final Long subOrderId;

    /**
     * 承运公司（录单必填，创建定型不可变）
     */
    private final String company;

    /**
     * 运单号（录单必填，创建定型不可变；仓配唯一业务凭证）
     */
    private final String trackingNo;

    /**
     * 履约状态（状态机唯一可变位，迁移经 {@link #advanceTo}）
     */
    private WaybillStatus status;

    /**
     * 轨迹事件列表（append-only：内部容器只经 {@link #advanceTo} 追加，
     * 外部读面经 {@link #getTracks()} 不可变快照冻结——无包外修改路径）
     */
    private final List<WaybillTrack> tracks;

    /**
     * 构造运单（创建/装载共用构造器）：形态不变量守卫（子单引用/公司/
     * 单号/状态/轨迹必填、末条轨迹状态与当前状态一致）。装载路径同样
     * 守卫形态不变量防脏数据；写路径校验（状态迁移守卫）不适用装载。
     *
     * @param id         运单主键（必填）
     * @param subOrderId 关联子单 ID（必填）
     * @param company    承运公司（必填非空）
     * @param trackingNo 运单号（必填非空）
     * @param status     履约状态（必填；须与末条轨迹状态一致）
     * @param tracks     轨迹事件列表（必填非空：创建自带初始轨迹，
     *                   时间线至少一个节点）
     */
    public Waybill(Long id, Long subOrderId, String company, String trackingNo,
                   WaybillStatus status, List<WaybillTrack> tracks) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                id, "运单主键不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                subOrderId, "运单关联子单 ID 不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_COMPANY_BLANK.code(),
                company != null && !company.isBlank(), "承运公司不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_TRACKING_NO_BLANK.code(),
                trackingNo != null && !trackingNo.isBlank(), "运单号不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                status, "运单状态不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                tracks != null && !tracks.isEmpty(), "运单轨迹列表不能为空（至少一条初始轨迹）");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                tracks.stream().allMatch(track -> track != null && track.getWaybillId() != null
                        && track.getWaybillId().equals(id)),
                "运单轨迹条目必须归属本运单");
        final WaybillTrack last = tracks.get(tracks.size() - 1);
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                last.getStatus() == status,
                "运单末条轨迹状态必须与当前状态一致：{} != {}",
                last.getStatus(), status);
        this.id = id;
        this.subOrderId = subOrderId;
        this.company = company;
        this.trackingNo = trackingNo;
        this.status = status;
        this.tracks = new ArrayList<>(tracks);
    }

    /**
     * 运单主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 关联子单 ID（跨聚合引用）。
     *
     * @return 子单 ID
     */
    public Long getSubOrderId() {
        return subOrderId;
    }

    /**
     * 承运公司。
     *
     * @return 公司名
     */
    public String getCompany() {
        return company;
    }

    /**
     * 运单号。
     *
     * @return 运单号
     */
    public String getTrackingNo() {
        return trackingNo;
    }

    /**
     * 履约状态。
     *
     * @return 状态
     */
    public WaybillStatus getStatus() {
        return status;
    }

    /**
     * 轨迹事件列表（append-only 不可变视图：外部读取安全、不可追加/
     * 不可修改——追加只经 {@link #advanceTo}）。
     *
     * @return 轨迹列表快照
     */
    public List<WaybillTrack> getTracks() {
        return List.copyOf(tracks);
    }

    /**
     * 在途判定：未签收（待发货/已发货/运输中）即在途。
     * <p>
     * 「一子单一在途运单」不变量（重复发货拒绝）的判定面：发货编排在
     * 创建运单前按 {@code findInTransitBySubOrderId} 查询命中在途运单
     * 即拒绝（logistics.sub_order_conflict）；签收（delivered）后不再
     * 在途。
     *
     * @return true=在途（未签收）；false=已签收
     */
    public boolean isInTransit() {
        return status != WaybillStatus.DELIVERED;
    }

    /**
     * 状态推进：追加一条轨迹并前移到轨迹携带的目标状态。
     * <p>
     * 守卫（全部收敛在本方法，非法迁移抛
     * {@link com.nona.exceptions.BusinessException}，业务码
     * {@code logistics.status_illegal}）：
     * <ol>
     *     <li>轨迹非空；</li>
     *     <li>归属校验：轨迹 waybillId 与运单主键一致（不符为装配错误
     *         拒绝——防他单轨迹推进到本单）；</li>
     *     <li>状态只能相邻前进：目标状态必须等于当前状态的下一状态
     *         （待发货→已发货→运输中→已签收；跳级/重复/回退/终态推进
     *         均为非法迁移）；</li>
     *     <li>推进成功后轨迹追加（append-only，内部容器）且状态前移到
     *         目标状态，后续推进必须基于新状态。</li>
     * </ol>
     *
     * @param track 目标状态轨迹条目（必填，归属本运单）
     */
    public void advanceTo(WaybillTrack track) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_STATUS_ILLEGAL.code(),
                track, "推进轨迹不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_STATUS_ILLEGAL.code(),
                track.getWaybillId() != null && track.getWaybillId().equals(id),
                "推进轨迹必须归属本运单：轨迹归属 {} 与本运单主键 {} 不一致",
                track.getWaybillId(), id);
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_STATUS_ILLEGAL.code(),
                track.getStatus() != null && track.getStatus().ordinal() == status.ordinal() + 1,
                "状态只能相邻前进：{} 不能推进到 {}（仅允许 {}）", status, track.getStatus(),
                status == WaybillStatus.DELIVERED ? "无（终态闭合）"
                        : WaybillStatus.values()[status.ordinal() + 1]);
        tracks.add(track);
        this.status = track.getStatus();
    }
}