package com.nona.api.common;

import java.util.Optional;

/**
 * 统一业务错误码：按域分段编号。
 * <p>
 * 分段约定：{@code 1xxx} 通用段、{@code 2xxx} 商品域（catalog）、{@code 3xxx} 库存域
 * （inventory）、{@code 4xxx} 订单域（order）、{@code 5xxx} 支付域（payment）。后续域
 * （物流/身份/搜索等）按段基值递增扩展；域内错误码 = 段基值 + 域内序号，随各域实现
 * 逐步补充到本枚举。业务异常携带 {@link #code()}，响应体统一由 HttpResponse 承载。
 */
public enum ErrorCode {

    /**
     * 成功（与 HttpResponse 成功码一致）
     */
    SUCCESS(0, "success"),

    /**
     * 参数格式非法（JSR-380 校验失败等）
     */
    COMMON_INVALID_PARAM(1001, "invalid parameter"),

    /**
     * 未认证（缺失或非法凭证）
     */
    COMMON_UNAUTHORIZED(1002, "unauthorized"),

    /**
     * 无权限（已认证但角色不匹配，含封禁拦截）
     */
    COMMON_FORBIDDEN(1003, "forbidden"),

    /**
     * 资源不存在（含跨租户视角下的不可见）
     */
    COMMON_NOT_FOUND(1004, "resource not found"),

    ;

    /**
     * 商品域（catalog）段基值（2xxx）
     */
    public static final int SEGMENT_CATALOG = 2000;

    /**
     * 库存域（inventory）段基值（3xxx）
     */
    public static final int SEGMENT_INVENTORY = 3000;

    /**
     * 订单域（order）段基值（4xxx）
     */
    public static final int SEGMENT_ORDER = 4000;

    /**
     * 支付域（payment）段基值（5xxx）
     */
    public static final int SEGMENT_PAYMENT = 5000;

    /**
     * 域错误码基数（段间隔，段基值相差 1000）
     */
    public static final int SEGMENT_SIZE = 1000;

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 按数值反查错误码。
     *
     * @param code 业务错误码数值
     * @return 匹配的错误码；未知数值返回空 Optional
     */
    public static Optional<ErrorCode> of(int code) {
        for (final ErrorCode candidate : values()) {
            if (candidate.code == code) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * 错误码数值。
     *
     * @return 分段编号后的业务错误码
     */
    public int code() {
        return code;
    }

    /**
     * 默认提示信息：可直接返回调用方，或由调用方补充上下文后覆盖。
     *
     * @return 人类可读的默认错误描述
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}
