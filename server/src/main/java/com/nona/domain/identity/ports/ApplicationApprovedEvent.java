package com.nona.domain.identity.ports;

import com.nona.events.Event;

import java.time.Instant;

/**
 * 入驻申请审核通过领域事件（跨上下文契约，供后续版本接入的编排消费：
 * 同事务内创建店铺聚合 + 账号-店铺关联）。
 * <p>
 * 本事件仅负责发布（进程内总线，同步分发），消费方注册同名事件
 * 处理器；payload 携带创建店铺所需的最小定位信息（申请 ID / 提交实体 /
 * 店铺名），消费方需要更多资料时可经申请 ID 回查申请聚合。
 *
 * @author nona9961
 */
public final class ApplicationApprovedEvent implements Event<ApplicationApprovedEvent.ApplicationApprovedData> {

    /**
     * 事件类型（处理器注册键，与消费方契约一致）
     */
    public static final String TYPE = "ApplicationApproved";

    /**
     * 事件载荷：申请 ID / 提交实体 / 店铺名
     *
     * @param applicationId 申请 ID
     * @param accountId     提交实体（商家账号 ID）
     * @param shopName      店铺名（店铺初值）
     */
    public record ApplicationApprovedData(Long applicationId, Long accountId, String shopName) {
    }

    /**
     * 事件载荷
     */
    private final ApplicationApprovedData payload;

    /**
     * 事件发生时间戳
     */
    private final Instant timestamp;

    /**
     * 构造审核通过事件（审核动作发生时创建）。
     *
     * @param applicationId 申请 ID
     * @param accountId     提交实体（商家账号 ID）
     * @param shopName      店铺名
     */
    public ApplicationApprovedEvent(Long applicationId, Long accountId, String shopName) {
        this.payload = new ApplicationApprovedData(applicationId, accountId, shopName);
        this.timestamp = Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationApprovedData getPayload() {
        return payload;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant timestamp() {
        return timestamp;
    }
}