package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import org.springframework.stereotype.Component;

/**
 * 售罄/恢复事件统一触发点（领域服务）：任何库存变更操作（预占/确认
 * 扣减/预占回滚/手工调整）产流水的路径变更后统一调用一次
 * {@link #publishIfNeeded(InventoryLog)}——判定与发布收敛于本服务，
 * 四处操作路径不再各自内联判定（售罄定义单一来源，防语义漂移）。
 * <p>
 * 判定语义（基于流水行 before/after 三态快照，无需状态追踪与额外
 * 读取）：
 * <ol>
 *     <li>售罄：变更后 available=0 且 held=0 → 发布 {@link SelloutEvent}；
 *         预占/扣减/调整任一操作路径致此状态均触发（回滚路径可售只增
 *         不减，从语义上不会落入售罄——统一调用点天然覆盖，无需特判）；</li>
 *     <li>恢复：变更前已售罄（available=0 且 held=0）且变更后未售罄 →
 *         发布 {@link RestockEvent}（一期日志消费）；「此前已售罄」由
 *         before 快照直接判定——不依赖事件序推导或持久化状态列。</li>
 * </ol>
 * 调用时机：用例层在流水 append 之后、同事务内调用（发布动作本身不
 * 落库；事务提交后投递的语义由发布端口实现衔接）。
 *
 * @author nona9961
 */
@Component
public class InventoryEventRouter {

    /**
     * 事件发布端口（进程内发布，事务提交后投递）
     */
    private final InventoryEventPublisher publisher;

    /**
     * 构造统一触发点服务。
     *
     * @param publisher 事件发布端口
     */
    public InventoryEventRouter(InventoryEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 统一触发点：变更后判定并按需发布售罄/恢复事件。
     * <p>
     * 判定基于流水行 before/after 三态快照，不依赖状态追踪与额外读取：
     * 变更后 available=0 且 held=0 → 发布售罄；变更前已售罄且变更后
     * 未售罄 → 发布恢复；其余变更零发布。售罄与恢复的条件天然互斥
     * （after 不可能同时为 (0,0) 与非 (0,0)），每次变更至多发布一个
     * 事件，不会重复发布。
     *
     * @param log 本次变更产生的流水行（before/after 三态快照就位）
     */
    public void publishIfNeeded(InventoryLog log) {
        final boolean soldOut = log.getAfterAvailable() == 0 && log.getAfterHeld() == 0;
        if (soldOut) {
            publisher.publishSellout(new SelloutEvent(log.getSkuId()));
            return;
        }
        final boolean wasSoldOut = log.getBeforeAvailable() == 0 && log.getBeforeHeld() == 0;
        if (wasSoldOut) {
            publisher.publishRestock(new RestockEvent(log.getSkuId()));
        }
    }
}