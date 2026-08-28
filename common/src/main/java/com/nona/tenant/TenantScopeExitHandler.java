package com.nona.tenant;

/**
 * 作用域退出通知 SPI（存储领域模型 I2：缓存与视角一致）。
 * <p>
 * 提权（{@code elevated}）/ 读放行（{@code withReadBypass}）作用域退出时（含异常路径）调用；
 * 实现方清理当前线程数据层会话缓存——避免「放行读入的异租户实体在过滤恢复后滞留一级缓存」
 * （背景：Hibernate 一级缓存 key 不含 filter，filter 只参与 SQL 渲染——放行读入的异租户实体
 * 若不清理，过滤恢复后仍可从一级缓存命中，造成跨租户读泄露）。
 * <p>
 * 零依赖的纯 SPI：不感知 Spring/JPA 与具体存储实现，由实现层（JPA / 未来 MyBatis 适配层）自行注册
 * （先例：静态 volatile + {@code @PostConstruct} 自注册模式）。
 *
 * @author nona9961
 */
@FunctionalInterface
public interface TenantScopeExitHandler {

    /**
     * 提权/读放行作用域退出时调用（含异常路径）；实现方清理当前线程数据层会话缓存。
     */
    void onScopeExited();
}
