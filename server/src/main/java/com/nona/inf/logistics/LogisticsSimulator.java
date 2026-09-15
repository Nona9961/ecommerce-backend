package com.nona.inf.logistics;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.factory.WaybillFactory;
import com.nona.domain.logistics.ports.WaybillDelivered;
import com.nona.domain.logistics.ports.WaybillDeliveredPublisher;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.inf.context.TrackingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物流模拟推进器（写真模拟物流：真实电商物流状态由承运方回推，本
 * 系统以模拟器按节奏自动推进运单状态机——待发货→已发货→运输中→
 * 已签收，模拟物流状态机、不接真实快递 API）：
 * 定时扫描在途运单，按推进节奏逐级推进状态并追加轨迹，推进至已签收
 * （终态）时发布 {@link WaybillDelivered} 签收事件供订单域自动完成
 * 联动。
 * <p>
 * <b>推进节奏（钉死，可配置）</b>——锚点 = 运单当前状态进入时刻
 * （末条轨迹发生时间）：
 * <ol>
 *     <li>待发货 → 已发货：<b>无延时</b>——商家发货编排同事务内已
 *         录入公司/单号，运单创建即代表货物已交承运（揽收与录单同时
 *         发生），下一轮扫描直接推进；</li>
 *     <li>已发货 → 运输中：进入已发货满 {@code shippedToInTransitDelay}
 *         （默认 30 秒，可配置）推进；</li>
 *     <li>运输中 → 已签收：进入运输中满 {@code inTransitToDeliveredDelay}
 *         （默认 60 秒，可配置）推进，并发布 {@link WaybillDelivered}
 *         签收事件（每运单至多一次）；</li>
 *     <li>已签收：终态闭合——跳过不推进、不重复发布（不重复语义的
 *         发布侧防线，消费侧另有幂等短路兜底）。</li>
 * </ol>
 * <b>编排序（Green 阶段实现依据）</b>：
 * <ol>
 *     <li>扫描入口（{@link #scanAndAdvance}，定时触发）：经
 *         {@link WaybillRepository#findInTransit()} 装载全部在途运单
 *         （不含已签收——仓储契约数据面；防线：扫描结果出现已签收
 *         运单时跳过，防御脏扫描面）；</li>
 *     <li>逐条按当前状态分派推进：到期判定 = 当前时刻 - 末条轨迹
 *         发生时间 ≥ 该状态对应节奏；到期则经
 *         {@link WaybillFactory#createTrack}（归属本运单/目标状态/
 *         当前时刻/推进描述）→ {@link Waybill#advanceTo}（相邻前进
 *         守卫 + 轨迹追加 + 状态前移，聚合内不变量）→
 *         {@link WaybillRepository#save} 落库；</li>
 *     <li>推进至已签收后同事务内发布 {@link WaybillDelivered}（发布
 *         不落库；事务提交后 AFTER_COMMIT 投递——订阅方只见已提交的
 *         签收状态）；</li>
 *     <li><b>事务边界</b>：每条运单独立事务（{@link TransactionTemplate}，
 *         扫描线程无请求事务）——推进 + 落库 + 发布调用同事务；单条
 *         异常该事务整体回滚、记录告警后<b>继续处理下一条</b>（不中断
 *         本轮批次），下轮重扫按状态快照幂等重试（已推进运单状态前移、
 *         未达终态重新到期判定）；</li>
 *     <li>扫描仓储异常（装载失败）向调度框架层透传（与超时调度引擎
 *         同构——调度方负责记录，下轮周期自然重试）。</li>
 * </ol>
 * <b>调度与装配</b>：定时触发位为
 * {@code @Scheduled(fixedDelayString = "${nona.logistics.scan-interval-ms:5000}")}
 * （每 5 秒一轮，可配置）——调度能力由装配门控 {@code SchedulingGateConfig}
 * 开启（{@code @EnableScheduling} 挂 {@code nona.scheduling.enabled=true}，
 * 键缺失/false 即不激活；dev=true 真实滴答、test=false 验收面确定性，
 * 冒烟手触 {@link #scanAndAdvance}，装配提示同超时调度引擎）；节奏可经配置
 * （{@code nona.logistics.shipped-to-in-transit-ms} /
 * {@code nona.logistics.in-transit-to-delivered-ms}）覆盖，默认
 * 30 秒/60 秒。时间源注入化（默认系统 UTC 时钟，测试注入固定时钟）。
 * <p>
 * 与超时调度引擎分工：本推进器只做物流状态机的模拟推进与签收事件
 * 发布；订单侧收货超时（逾期未确认自动完成）归超时调度引擎的收货
 * 超时处理器（与签收联动共享同一完成迁移，幂等短路互不重入）。
 *
 * @author nona9961
 */
@Component
@Slf4j
public class LogisticsSimulator {

    /**
     * 默认推进间隔：已发货 → 运输中（秒）
     */
    public static final Duration DEFAULT_SHIPPED_TO_IN_TRANSIT_DELAY = Duration.ofSeconds(30);

    /**
     * 默认推进间隔：运输中 → 已签收（秒）
     */
    public static final Duration DEFAULT_IN_TRANSIT_TO_DELIVERED_DELAY = Duration.ofSeconds(60);

    /**
     * 推进描述：待发货 → 已发货
     */
    static final String DESC_SHIPPED = "货物已发出（模拟推进）";

    /**
     * 推进描述：已发货 → 运输中
     */
    static final String DESC_IN_TRANSIT = "运输中（模拟推进）";

    /**
     * 推进描述：运输中 → 已签收
     */
    static final String DESC_DELIVERED = "已签收（模拟推进）";

    /**
     * 在途运单数据面（扫描锚点与落库）
     */
    private final WaybillRepository waybillRepository;

    /**
     * 运单工厂（推进轨迹条目装配）
     */
    private final WaybillFactory waybillFactory;

    /**
     * 签收事件发布端口（推进至已签收后同事务发布）
     */
    private final WaybillDeliveredPublisher waybillDeliveredPublisher;

    /**
     * 事务模板（每条运单独立推进事务）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 推进间隔：已发货 → 运输中（可配置，默认 30 秒）
     */
    private final Duration shippedToInTransitDelay;

    /**
     * 推进间隔：运输中 → 已签收（可配置，默认 60 秒）
     */
    private final Duration inTransitToDeliveredDelay;

    /**
     * 时间源（默认系统 UTC 时钟；测试注入固定时钟）
     */
    private final Clock clock;

    /**
     * 装配构造（生产语义：默认节奏 + 系统时钟；节奏可经配置覆盖——
     * 随仓储接线阶段以参数装配恢复）。
     *
     * @param waybillRepository        在途运单数据面
     * @param waybillFactory           运单工厂（轨迹装配）
     * @param waybillDeliveredPublisher 签收事件发布端口
     * @param transactionTemplate      事务模板（单条推进事务）
     */
    @Autowired
    public LogisticsSimulator(WaybillRepository waybillRepository,
                              WaybillFactory waybillFactory,
                              WaybillDeliveredPublisher waybillDeliveredPublisher,
                              TransactionTemplate transactionTemplate) {
        this(waybillRepository, waybillFactory, waybillDeliveredPublisher,
                transactionTemplate, DEFAULT_SHIPPED_TO_IN_TRANSIT_DELAY,
                DEFAULT_IN_TRANSIT_TO_DELIVERED_DELAY, Clock.systemUTC());
    }

    /**
     * 定制构造（测试注入固定时钟与节奏参数验证推进时间语义；
     * 单测装配点）。
     *
     * @param waybillRepository         在途运单数据面
     * @param waybillFactory            运单工厂（轨迹装配）
     * @param waybillDeliveredPublisher 签收事件发布端口
     * @param transactionTemplate       事务模板（单条推进事务）
     * @param shippedToInTransitDelay   已发货 → 运输中间隔（必填非负）
     * @param inTransitToDeliveredDelay 运输中 → 已签收间隔（必填非负）
     * @param clock                     时间源（必填）
     */
    public LogisticsSimulator(WaybillRepository waybillRepository,
                              WaybillFactory waybillFactory,
                              WaybillDeliveredPublisher waybillDeliveredPublisher,
                              TransactionTemplate transactionTemplate,
                              Duration shippedToInTransitDelay,
                              Duration inTransitToDeliveredDelay,
                              Clock clock) {
        this.waybillRepository = waybillRepository;
        this.waybillFactory = waybillFactory;
        this.waybillDeliveredPublisher = waybillDeliveredPublisher;
        this.transactionTemplate = transactionTemplate;
        this.shippedToInTransitDelay = shippedToInTransitDelay;
        this.inTransitToDeliveredDelay = inTransitToDeliveredDelay;
        this.clock = clock;
    }

    /**
     * 定时扫描推进入口（调度装配位：{@code @Scheduled} 经装配门控激活；
     * 单测直接调用）。
     * <p>
     * 编排序见类 javadoc：全量在途装载 → 逐条按当前状态分派到期推进
     * （待发货无延时直推 / 已发货满 30 秒推进 / 运输中满 60 秒推进并
     * 发布签收事件 / 已签收终态跳过）→ 每条独立事务，单条失败告警后
     * 继续下一条。
     * <p>
     * 跟踪作用域：本方法是调度面入口（{@code @Scheduled} 线程无入口组件绑定
     * TRACKING scope，而推进落库（运单/轨迹变更集驱动）要求
     * {@code TrackingContext.withScope}——fail-closed）→ 方法内绑定系统上下文
     * （与测试手触 withScope 同形态；嵌套绑定安全）。
     */
    @Scheduled(fixedDelayString = "${nona.logistics.scan-interval-ms:5000}")
    public void scanAndAdvance() {
        TrackingContext.withScope(() -> {
            final List<Waybill> inTransit = waybillRepository.findInTransit();
            final Instant now = clock.instant();
            for (final Waybill waybill : inTransit) {
                try {
                    transactionTemplate.execute(status -> advanceOne(waybill, now));
                } catch (final RuntimeException ex) {
                    log.warn("[logistics-simulator] waybill advance failed, will be retried next scan: waybillId={}, msg={}",
                            waybill.getId(), ex.getMessage(), ex);
                }
            }
        });
    }

    /**
     * 单条到期判定（状态分派语义钉死，供测试直测；推进动作在
     * {@link #scanAndAdvance} 内逐条执行,本方法无副作用）。
     *
     * @param waybill 在途运单（必填）
     * @param now     判定时刻（必填）
     * @return true=到期应推进（含待发货无延时直推）；false=未到期或
     * 终态（已签收）跳过
     */
    public boolean isDue(Waybill waybill, Instant now) {
        if (waybill.getStatus() == WaybillStatus.DELIVERED) {
            return false;
        }
        if (waybill.getStatus() == WaybillStatus.PENDING_SHIPMENT) {
            return true;
        }
        final Instant anchor = waybill.getTracks().getLast().getOccurredAt()
                .atZone(clock.getZone()).toInstant();
        final Duration delay = waybill.getStatus() == WaybillStatus.SHIPPED
                ? shippedToInTransitDelay
                : inTransitToDeliveredDelay;
        return Duration.between(anchor, now).compareTo(delay) >= 0;
    }

    /**
     * 单条运单推进（在独立推进事务内执行）：到期判定通过后装配目标
     * 状态轨迹（目标状态 = 当前状态的下一状态；轨迹时刻 = 本轮扫描
     * 时刻；推进描述按目标状态定型）→ 聚合相邻前进 → 落库 → 推进至
     * 已签收时同事务发布签收事件（每运单至多一次，终态闭合）。未到
     * 期/终态零动作返回。
     *
     * @param waybill 在途运单
     * @param now     本轮扫描时刻
     * @return 恒为 null（事务模板回调形态）；推进动作见参数侧运单
     */
    private Void advanceOne(Waybill waybill, Instant now) {
        if (!isDue(waybill, now)) {
            return null;
        }
        final WaybillStatus target = WaybillStatus.values()[waybill.getStatus().ordinal() + 1];
        final String description = switch (target) {
            case SHIPPED -> DESC_SHIPPED;
            case IN_TRANSIT -> DESC_IN_TRANSIT;
            case DELIVERED -> DESC_DELIVERED;
            default -> throw new IllegalStateException("无推进描述的目标状态：" + target);
        };
        final WaybillTrack track = waybillFactory.createTrack(waybill, target,
                LocalDateTime.ofInstant(now, clock.getZone()), description);
        waybill.advanceTo(track);
        waybillRepository.save(waybill);
        if (target == WaybillStatus.DELIVERED) {
            waybillDeliveredPublisher.publishWaybillDelivered(
                    new WaybillDelivered(waybill.getId(), waybill.getSubOrderId()));
        }
        return null;
    }
}