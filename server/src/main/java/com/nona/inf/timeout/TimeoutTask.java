package com.nona.inf.timeout;

/**
 * 超时任务候选：一轮扫描发现的到期记录视图。
 * <p>
 * 引擎不感知业务表形态：id 是承载截止时间列的业务主行主键
 * （乐观锁认领与清除的定位键），target 是交给处理器执行的业务
 * 对象引用（泛型：由数据端口构造，对引擎透明）。
 *
 * @param <T> 业务对象引用类型
 */
public record TimeoutTask<T>(Long id, T target) {
}