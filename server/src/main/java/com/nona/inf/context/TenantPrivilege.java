package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.tenant.TenantScopeExitHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 租户提权与读放行作用域（纯状态）：
 * <ul>
 *   <li>{@link #elevated(Runnable)} — 提权：作用域内写门禁放行实体显式异租户，读路径由持久化适配层自行放行</li>
 *   <li>{@link #withReadBypass(Runnable)} — 读放行：作用域内读路径关闭租户过滤（{@code @CrossTenant} 注解使用），写门禁不受影响</li>
 * </ul>
 * 持久化适配层通过 {@link #isAnyReadBypassActive()} 在每次数据访问时自查状态决定过滤行为（设计 D1/D2）。
 * 基于 ScopedValue：出作用域自动恢复、块内不可篡改、默认不跨线程传播。
 *
 * @author nona9961
 */
@Slf4j
@ScaffoldGenerated
public final class TenantPrivilege {

    private static final ScopedValue<Boolean> ELEVATED = ScopedValue.newInstance();

    /**
     * 读放行状态（{@code @CrossTenant} 作用域）；仅影响读路径过滤，不参与写门禁判断。
     */
    private static final ScopedValue<Boolean> READ_BYPASS = ScopedValue.newInstance();

    /**
     * 租户上下文访问器（Spring 组件初始化时注册，审计日志取 identity/tenantID 用）；{@code null} 表示未注册。
     */
    private static volatile TenantContextAccessor tenantContextAccessor;

    /**
     * 作用域退出处理器：作用域退出（含异常路径）→ 数据层会话缓存清理（缓存与视角一致：
     * 放行阶段读入的异租户实体不得在过滤恢复后滞留）。
     * JPA 适配层 {@code @PostConstruct} 自注册；{@code null} 表示未注册（纯单测环境 no-op）。
     */
    private static volatile TenantScopeExitHandler scopeExitHandler;

    /**
     * 私有构造：纯静态工具类，禁止实例化。
     */
    private TenantPrivilege() {
    }

    /**
     * 进入提权作用域执行无返回值操作。
     *
     * @param action 提权操作
     */
    public static void elevated(Runnable action) {
        log.debug("[TenantPrivilege] elevated scope enter, action={}, alreadyActive={}, identity={}, tenantID={}, at={}",
                action.getClass().getSimpleName(), isActive(), resolveIdentity(), resolveTenantID(), Instant.now());
        try {
            ScopedValue.where(ELEVATED, Boolean.TRUE).run(action);
        }
        finally {
            notifyScopeExited();
        }
    }

    /**
     * 进入提权作用域执行有返回值操作。
     *
     * @param action 提权操作
     * @param <T>    返回类型
     * @return 操作结果
     * @throws Exception 操作抛出的异常原样透传
     */
    public static <T> T elevated(Callable<T> action) throws Exception {
        log.debug("[TenantPrivilege] elevated scope enter, action={}, alreadyActive={}, identity={}, tenantID={}, at={}",
                action.getClass().getSimpleName(), isActive(), resolveIdentity(), resolveTenantID(), Instant.now());
        try {
            return ScopedValue.where(ELEVATED, Boolean.TRUE).call(action::call);
        }
        finally {
            notifyScopeExited();
        }
    }

    /**
     * 进入读放行作用域执行无返回值操作（仅关闭读路径租户过滤；写门禁不受影响）。
     *
     * @param action 读放行操作
     */
    public static void withReadBypass(Runnable action) {
        log.debug("[TenantPrivilege] read-bypass scope enter, action={}, alreadyActive={}, identity={}, tenantID={}, at={}",
                action.getClass().getSimpleName(), isReadBypassActive(), resolveIdentity(), resolveTenantID(), Instant.now());
        try {
            ScopedValue.where(READ_BYPASS, Boolean.TRUE).run(action);
        }
        finally {
            notifyScopeExited();
        }
    }

    /**
     * 进入读放行作用域执行有返回值操作（仅关闭读路径租户过滤；写门禁不受影响）。
     *
     * @param action 读放行操作
     * @param <T>    返回类型
     * @return 操作结果
     * @throws Exception 操作抛出的异常原样透传
     */
    public static <T> T withReadBypass(Callable<T> action) throws Exception {
        log.debug("[TenantPrivilege] read-bypass scope enter, action={}, alreadyActive={}, identity={}, tenantID={}, at={}",
                action.getClass().getSimpleName(), isReadBypassActive(), resolveIdentity(), resolveTenantID(), Instant.now());
        try {
            return ScopedValue.where(READ_BYPASS, Boolean.TRUE).call(action::call);
        }
        finally {
            notifyScopeExited();
        }
    }

    /**
     * 先进入提权作用域再开启事务——保证 session 创建时租户模式已定型。
     * 提权绑定与事务边界合并为同一结构化作用域；事务回调内抛出的 {@link RuntimeException}
     * （含 {@link com.nona.exceptions.BusinessException}）原样透传，仅受检异常包装为
     * {@link IllegalStateException}。
     *
     * @param transactionTemplate 事务模板
     * @param action              提权事务操作
     * @param <T>                 返回类型
     * @return 事务执行结果
     * @throws Exception 操作抛出的异常原样透传
     * @apiNote 本方法的作用域退出晚于事务提交（EM 已解绑）→ 退出通知
     *          {@code notifyScopeExited()} 中 handler 查 hasResource 恒 false → 空转属预期
     *          （缓存随事务消亡，无泄露可清）。真正触发 flush+clear 的是事务内嵌套的
     *          放行/提权作用域（如 {@code withReadBypass} / {@code elevated} 内联于事务回调）。
     */
    public static <T> T elevatedInTransaction(TransactionTemplate transactionTemplate,
                                              Callable<T> action) throws Exception {
        return elevated(() -> transactionTemplate.execute(status -> {
            try {
                return action.call();
            }
            catch (RuntimeException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IllegalStateException("elevated transaction failed", e);
            }
        }));
    }

    /**
     * 当前是否处于提权作用域（写门禁的唯一判断源）。
     *
     * @return 提权中返回 true
     */
    public static boolean isActive() {
        return ELEVATED.isBound() && ELEVATED.get();
    }

    /**
     * 当前是否处于读放行作用域（{@code @CrossTenant}）。
     *
     * @return 读放行中返回 true
     */
    public static boolean isReadBypassActive() {
        return READ_BYPASS.isBound() && READ_BYPASS.get();
    }

    /**
     * 当前是否处于任一读放行状态（提权或读放行作用域）——持久化适配层自查读隔离的唯一判断源。
     *
     * @return 任一读放行激活返回 true
     */
    public static boolean isAnyReadBypassActive() {
        return isActive() || isReadBypassActive();
    }

    /**
     * 注册租户上下文访问器（供审计日志解析调用者身份与当前租户）；传 {@code null} 忽略（幂等）。
     *
     * @param accessor 访问器；{@code null} 忽略
     */
    public static void registerTenantContextAccessor(TenantContextAccessor accessor) {
        if (accessor != null) {
            tenantContextAccessor = accessor;
        }
    }

    /**
     * 注册作用域退出处理器（I2：提权/读放行作用域退出 → 数据层会话缓存清理）；传 {@code null} 忽略（幂等）。
     * <p>
     * 后注册覆盖先注册（单实现先例：JPA 适配层自注册）；{@code null} 不覆盖——保持
     * 纯单测环境（无 Spring 容器、无 handler 注册）下退出通知为 no-op 的既有契约。
     *
     * @param handler 退出处理器；{@code null} 忽略
     */
    public static void registerScopeExitHandler(TenantScopeExitHandler handler) {
        if (handler != null) {
            scopeExitHandler = handler;
        }
    }

    /**
     * 作用域退出通知（finally 语义，异常路径同样通知）：handler 未注册 → 直接返回；
     * handler 清理失败 → 记录日志不重抛（不吞业务异常、不覆盖作用域内原始异常）。
     */
    private static void notifyScopeExited() {
        final TenantScopeExitHandler handler = scopeExitHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.onScopeExited();
        }
        catch (RuntimeException e) {
            log.error("[TenantPrivilege] scope-exit cleanup failed", e);
        }
    }

    /**
     * 解析审计日志用的调用者身份；访问器未注册或身份缺失时返回 {@code "unknown"}。
     *
     * @return 调用者身份；不可用时返回 {@code "unknown"}
     */
    private static String resolveIdentity() {
        if (tenantContextAccessor == null) {
            return "unknown";
        }
        return Objects.requireNonNullElse(tenantContextAccessor.getIdentity(), "unknown");
    }

    /**
     * 解析审计日志用的当前租户；访问器未注册或租户缺失时返回 {@code "unknown"}。
     *
     * @return 当前租户；不可用时返回 {@code "unknown"}
     */
    private static String resolveTenantID() {
        if (tenantContextAccessor == null) {
            return "unknown";
        }
        return Objects.requireNonNullElse(tenantContextAccessor.getTenantID(), "unknown");
    }
}
