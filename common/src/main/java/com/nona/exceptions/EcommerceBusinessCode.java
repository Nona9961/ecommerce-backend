package com.nona.exceptions;

/**
 * ecommerce 业务域码及其默认 HTTP 状态映射。
 * <p>
 * 域码命名：小写点分 {@code domain.reason}，只增不改。段归属：
 * <ul>
 *     <li>{@code auth.*}：认证/授权域——未认证 {@code auth.unauthorized}、封禁与权限不足统一
 *         {@code auth.forbidden}（Security 链与登录用例同一语义）、凭证错误、注册用户名冲突</li>
 *     <li>{@code identity.*}：身份域——买家地址簿地址不存在 {@code identity.address_not_found}</li>
 *     <li>{@code catalog.*} / {@code inventory.*} / {@code order.*} / {@code payment.*}：
 *         各业务域段基码占位，随各域 WU 扩展（只增不改）</li>
 * </ul>
 * 通用码（{@code generic.*}）由 {@link BusinessCode} 承载。状态解析链：
 * 域码命中返回映射 → 未命中委托 {@link BusinessCode#defaultStatus(String)}（generic 码）→
 * 仍未知兜底 500（fail-closed，不抛异常）。
 *
 * @author nona9961
 */
public enum EcommerceBusinessCode {

    /**
     * 未认证（缺失或非法凭证）。
     */
    AUTH_UNAUTHORIZED("auth.unauthorized", 401),

    /**
     * 无权限或账号被禁止（封禁拦截与角色不匹配统一，Security 链与登录用例一致）。
     */
    AUTH_FORBIDDEN("auth.forbidden", 403),

    /**
     * 凭证错误（登录失败；统一消息防账号存在性泄露）。
     */
    AUTH_BAD_CREDENTIALS("auth.bad_credentials", 400),

    /**
     * 注册用户名冲突（同 type 联合唯一）。
     */
    AUTH_USERNAME_CONFLICT("auth.username_conflict", 400),

    /**
     * 身份域：买家地址簿目标地址不存在（编辑/删除/设置默认命中归属不明的地址）。
     */
    IDENTITY_ADDRESS_NOT_FOUND("identity.address_not_found", 404),

    /**
     * 身份域：入驻申请已存在（每个提交实体至多一个申请，提交/重提路径的账号冲突）。
     */
    IDENTITY_ONBOARDING_CONFLICT("identity.onboarding_conflict", 400),

    /**
     * 身份域：入驻申请不存在（按 ID 操作命中归属不明或不存在的申请）。
     */
    IDENTITY_ONBOARDING_NOT_FOUND("identity.onboarding_not_found", 404),

    /**
     * 身份域：入驻申请非法状态迁移（非 pending 审核 / 非 rejected 重提 / 终态修改 / 缺驳回原因）。
     */
    IDENTITY_ONBOARDING_STATE("identity.onboarding_state", 400),

    /**
     * 商品域占位基码：资源不存在。
     */
    CATALOG_NOT_FOUND("catalog.not_found", 404),

    /**
     * 库存域占位基码：资源不存在。
     */
    INVENTORY_NOT_FOUND("inventory.not_found", 404),

    /**
     * 订单域占位基码：资源不存在。
     */
    ORDER_NOT_FOUND("order.not_found", 404),

    /**
     * 支付域占位基码：资源不存在。
     */
    PAYMENT_NOT_FOUND("payment.not_found", 404);

    private final String code;

    private final int defaultStatus;

    EcommerceBusinessCode(String code, int defaultStatus) {
        this.code = code;
        this.defaultStatus = defaultStatus;
    }

    /**
     * 业务码字符串（小写点分）。
     *
     * @return 业务码值
     */
    public String code() {
        return code;
    }

    /**
     * 查询业务码对应的默认 HTTP 状态码。
     * <p>
     * 解析链：域码命中返回映射 → 未命中委托 {@link BusinessCode#defaultStatus(String)}
     * （generic 码）→ 仍未知兜底 500（fail-closed，不抛异常）。
     *
     * @param code 业务码字符串，可为 null
     * @return 映射状态码
     */
    public static int defaultStatus(String code) {
        for (EcommerceBusinessCode ebc : values()) {
            if (ebc.code.equals(code)) {
                return ebc.defaultStatus;
            }
        }
        return BusinessCode.defaultStatus(code);
    }
}
