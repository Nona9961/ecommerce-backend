package com.nona.inf.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度装配门控（全局调度能力唯一激活位）：一经加载，容器内全部
 * {@code @Scheduled} 生效——物流模拟推进器
 * {@code LogisticsSimulator#scanAndAdvance} 与超时引擎
 * {@code TimeoutScheduler#scan} 两个引擎的定时触发。
 * <p>
 * 仅当 {@code nona.scheduling.enabled=true} 时激活（不设 matchIfMissing：
 * 键缺失即不激活，prod 配置无此键 → 生产不开调度，保守安全）；
 * dev=true（demo 演示面真实滴答）、test=false（验收面确定性，冒烟
 * 手触 {@code processOne}/{@code scanAndAdvance}，不受调度节奏抖动）。
 *
 * @author nona9961
 */
@Configuration
@ConditionalOnProperty(name = "nona.scheduling.enabled", havingValue = "true")
@EnableScheduling
public class SchedulingGateConfig {
}