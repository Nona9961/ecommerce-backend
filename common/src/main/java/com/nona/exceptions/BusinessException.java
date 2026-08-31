package com.nona.exceptions;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 自定义业务异常类，用于表示业务逻辑中的异常情况
 * <p>
 * ControllerAdvice中会捕获此类的异常，并返回给前端
 */
@ScaffoldGenerated
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码（0 = 未指定，沿用默认失败语义；非 0 对应 {@code ErrorCode.code()}）
     */
    private final int code;

    /**
     * 消息内容应清晰描述具体的业务错误原因，将会展示给用户。
     *
     * @param message 异常消息内容，用于说明业务错误的具体原因和上下文信息
     */
    public BusinessException(String message) {
        this(message, 0);
    }

    /**
     * 携带业务错误码的构造：错误码由 {@code ErrorCode} 定义，响应层可据此输出稳定
     * 错误码与对应 HTTP 语义（如 COMMON_FORBIDDEN → 真实 HTTP 403）。
     *
     * @param message 异常消息内容，用于说明业务错误的具体原因和上下文信息
     * @param code    业务错误码（ErrorCode.code()）
     */
    public BusinessException(String message, int code) {
        super(message);
        this.code = code;
    }

    /**
     * 业务错误码。
     *
     * @return 业务错误码；未指定时返回 0
     */
    public int getCode() {
        return code;
    }
}
