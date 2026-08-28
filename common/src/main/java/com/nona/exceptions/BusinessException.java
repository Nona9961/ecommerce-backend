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
     * 消息内容应清晰描述具体的业务错误原因，将会展示给用户。
     *
     * @param message 异常消息内容，用于说明业务错误的具体原因和上下文信息
     */
    public BusinessException(String message) {
        super(message);
    }
}
