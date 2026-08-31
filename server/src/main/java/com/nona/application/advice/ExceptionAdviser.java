package com.nona.application.advice;

import com.nona.api.HttpResponse;
import com.nona.api.common.ErrorCode;
import com.nona.exceptions.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 全局异常处理器：统一捕获异常并转换为 {@link HttpResponse} 返回。
 * <p>
 * 业务异常以 HTTP 200 + 业务状态码形式返回；唯一例外是携带
 * {@link ErrorCode#COMMON_FORBIDDEN} 的业务异常（如封禁账号登录拒绝）——
 * 按认证语义输出真实 HTTP 403 + 稳定业务码；内部错误不向调用方泄露细节。
 */
@RestControllerAdvice
@Slf4j
@ScaffoldGenerated
public class ExceptionAdviser {

    /**
     * 处理业务异常：消息透传给调用方；携带 COMMON_FORBIDDEN 错误码时
     * 输出真实 HTTP 403（与认证链封禁语义一致），其余保持 HTTP 200。
     *
     * @param e 业务异常
     * @return 失败响应（携带异常消息）
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<HttpResponse<?>> handleBusinessException(BusinessException e) {
        if (e.getCode() == ErrorCode.COMMON_FORBIDDEN.code()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new HttpResponse<>(
                    ErrorCode.COMMON_FORBIDDEN.code(),
                    ErrorCode.COMMON_FORBIDDEN.defaultMessage(),
                    false,
                    null));
        }
        return ResponseEntity.ok(HttpResponse.fail(e.getMessage()));
    }

    /**
     * 处理方法参数校验异常：返回字段级错误 map。
     *
     * @param ex 参数校验异常
     * @return 失败响应（携带字段错误 map）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public HttpResponse<?> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        log.error("illegal args :{}", errors);
        return HttpResponse.fail(errors);
    }

    /**
     * 处理表单绑定异常：返回字段级错误 map。
     *
     * @param ex 绑定异常
     * @return 失败响应（携带字段错误 map）
     */
    @ExceptionHandler(BindException.class)
    public HttpResponse<?> handleBindException(BindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        log.error("illegal args :{}", errors);
        return HttpResponse.fail(errors);
    }

    /**
     * 兜底处理运行时异常：记录堆栈，返回通用错误消息（不泄露内部细节）。
     *
     * @param e 运行时异常
     * @return 失败响应（通用错误消息）
     */
    @ExceptionHandler(RuntimeException.class)
    public HttpResponse<?> handleRuntimeException(RuntimeException e) {
        log.error("unhandled exception : {}", e.getMessage());
        log.error("stack is ", e);
        return HttpResponse.fail("Severe internal error. Please retry later.");
    }
}
