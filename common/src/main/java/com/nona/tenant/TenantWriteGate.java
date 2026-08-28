package com.nona.tenant;

import com.nona.util.BusinessAssert;

/**
 * 租户写门禁：两条件判定（提权状态 × 实体归属）。存储无关，零 Spring/JPA 依赖。
 * <p>
 * 纯函数：输入 {@code (实体归属, 当前视角租户, 提权状态)} → 返回「需注入的租户值」或 {@code null}（放行）；
 * 拒绝通过抛出 {@link com.nona.exceptions.BusinessException} 表达（fail-fast）。载体（AOP 切面 / 未来 MyBatis
 * Interceptor）负责取实体、调本类判定、按返回值执行注入。
 * <p>
 * 判定分支（两条件判定：提权状态 × 实体归属）：
 * <ul>
 *   <li>哨兵前置：MISSING/ROOT 不可作实体归属（拒绝，防污染）</li>
 *   <li>提权 + 空归属 → 拒绝（插入归属必得：归属不可发明，fail-closed）</li>
 *   <li>提权 + 显式归属 → 放行并保留原值（归属不变：不写入）</li>
 *   <li>非提权 + 视角租户缺失 → 拒绝（视角缺失 fail-closed）</li>
 *   <li>非提权 + 空归属 → 注入视角租户（视角补全）</li>
 *   <li>非提权 + 归属不一致 → 拒绝（写目标合法性）</li>
 * </ul>
 *
 * @author nona9961
 */
public final class TenantWriteGate {

    /**
     * 视角缺失占位（fail-closed）：租户缺失时 Hibernate resolver 返回该值，不放行 tenant-scoped 数据。
     * <p>
     * 权威定义处：server 侧 {@code TenantContextAccessor.MISSING_TENANT_ID} 为转发常量。
     */
    public static final String MISSING_TENANT_ID = "__MISSING_TENANT__";

    /**
     * 全量视角（root）：读放行/提权作用域下 Hibernate resolver 返回该值以绕过 discriminator 过滤。
     * <p>
     * 权威定义处：server 侧 {@code ThreadContextTenantIdentifierResolver.ROOT_TENANT_ID} 为转发常量。
     */
    public static final String ROOT_TENANT_ID = "__ROOT_TENANT__";

    /**
     * 私有构造：纯函数工具类，禁止实例化。
     */
    private TenantWriteGate() {
    }

    /**
     * 归一化：{@code null} 或空白字符串视为缺失（返回 {@code null}）。
     * <p>
     * 语义迁移自重构前的 {@code TenantRepositoryAspect#normalizeTenantID}（行为不变）：
     * 空白在判定前归一为空归属：提权下空归属被拒绝，非提权下空归属被注入视角租户。
     *
     * @param tenantID 原始 tenantID
     * @return 归一化后的 tenantID；缺失则返回 {@code null}
     */
    private static String normalizeTenantID(String tenantID) {
        if (tenantID == null || tenantID.isBlank()) {
            return null;
        }
        return tenantID;
    }

    /**
     * 两条件判定：依「提权状态 × 实体归属」判定本次写目标。
     *
     * @param ownedTenantId 实体归属（tenant-scoped PO 的 tenantID）；可为 {@code null}
     * @param contextTenant 当前视角租户（上下文）；可为 {@code null}
     * @param elevated      是否处于提权作用域（写门禁唯一判断源）
     * @return 需注入实体的租户值（视角补全注入时）；否则返回 {@code null}（放行，不写入）
     * @throws com.nona.exceptions.BusinessException 拒绝时抛出（空归属/哨兵/视角缺失/归属不一致）
     */
    public static String decideInjection(String ownedTenantId,
                                         String contextTenant,
                                         boolean elevated) {
        final String owned = normalizeTenantID(ownedTenantId); // 空白归一为空归属：提权拒绝 / 非提权注入
        assertNotSentinel(owned);                              // 哨兵拒绝前置：提权/非提权统一语义
        if (elevated) {
            if (owned == null) {
                throw BusinessAssert.generateExByMsg(
                        "elevated write requires explicit tenantId but tenant is missing or blank"); // 空归属拒绝
            }
            return null;                                       // 显式归属放行（归属不变，不写入）
        }
        if (contextTenant == null) {
            throw BusinessAssert.generateExByMsg("tenantID is required for tenant-scoped write");   // 视角缺失拒绝
        }
        if (owned == null) {
            return contextTenant;                              // 视角补全注入（含空白归一）
        }
        if (!contextTenant.equals(owned)) {
            throw BusinessAssert.generateExByMsg(
                    "cross-tenant write is forbidden. currentTenant={}, entityTenant={}",
                    contextTenant, owned);                     // 归属不一致拒绝
        }
        return null;
    }

    /**
     * 判断是否为哨兵值（MISSING/ROOT）。
     *
     * @param v 待判断值；{@code null} 返回 false
     * @return 是哨兵值返回 true
     */
    private static boolean isSentinel(String v) {
        return MISSING_TENANT_ID.equals(v) || ROOT_TENANT_ID.equals(v);
    }

    /**
     * 哨兵断言：实体归属不可为哨兵值（防污染）。{@code null} 天然跳过（空归属由判定的提权/非提权分支处理）。
     *
     * @param v 归一化后的实体归属；可为 {@code null}
     * @throws com.nona.exceptions.BusinessException 为哨兵值时抛出
     */
    private static void assertNotSentinel(String v) {
        if (v != null && isSentinel(v)) {
            throw BusinessAssert.generateExByMsg("invalid tenantId cannot be used as entity tenant: {}", v);
        }
    }
}
