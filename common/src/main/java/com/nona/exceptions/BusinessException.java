package com.nona.exceptions;

import com.nona.annotation.ScaffoldGenerated;

/**
 * 业务异常载体：携带业务码与 HTTP 状态码，message 面向 API 消费方。
 * <p>
 * 与脚手架版本同构，差异仅在状态解析链：业务码默认映射经
 * {@link EcommerceBusinessCode#defaultStatus(String)}（域码 → 本模块
 * {@link BusinessCode} generic 码 → 兜底 500，fail-closed 不抛异常）。
 *
 * @author nona9961
 */
@ScaffoldGenerated
public class BusinessException extends RuntimeException {

    /**
     * 业务码，message-only 构造器创建时为 null。
     */
    private final String businessCode;

    /**
     * 解析后的 HTTP 状态码（解析规则见各构造器）。
     */
    private final int httpStatus;

    /**
     * 仅消息构造器（向后兼容）：业务码为 null，状态兜底 500。
     *
     * @param message 业务错误原因
     */
    public BusinessException(String message) {
        this(null, message, EcommerceBusinessCode.defaultStatus(null));
    }

    /**
     * 业务码 + 消息构造器：状态按业务码默认映射解析。
     *
     * @param businessCode 业务码（小写点分，见 {@link EcommerceBusinessCode}）
     * @param message      业务错误原因
     */
    public BusinessException(String businessCode, String message) {
        this(businessCode, message, EcommerceBusinessCode.defaultStatus(businessCode));
    }

    /**
     * 业务码 + 消息 + 显式状态构造器：状态以显式指定为准。
     *
     * @param businessCode 业务码（小写点分，见 {@link EcommerceBusinessCode}）
     * @param message      业务错误原因
     * @param httpStatus   显式 HTTP 状态码，优先于业务码默认映射
     */
    public BusinessException(String businessCode, String message, int httpStatus) {
        super(message);
        this.businessCode = businessCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 业务码访问器。
     *
     * @return 业务码
     */
    public String getBusinessCode() {
        return businessCode;
    }

    /**
     * HTTP 状态码访问器。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
