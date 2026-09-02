package com.nona.application.advice;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.api.HttpResponse;
import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 全局异常处理器：统一捕获异常并转换为真实 HTTP 状态码 + {@link HttpResponse} 响应体。
 * <p>
 * HTTP 语义（状态映射表）：
 * <ul>
 *     <li>{@link BusinessException} → 状态 = {@code e.getHttpStatus()}（显式状态 &gt; 业务码默认映射 &gt; 兜底 500）；
 *         响应体携带业务码；businessCode 为 null（message-only）时兜底
 *         {@link BusinessCode#INTERNAL_ERROR}（业务码状态解析链见
 *         {@link com.nona.exceptions.EcommerceBusinessCode#defaultStatus(String)}）</li>
 *     <li>{@link MethodArgumentNotValidException} / {@link BindException} → 400 + 字段错误 map
 *         （{@link BusinessCode#VALIDATION_FAILED}）</li>
 *     <li>{@link HttpMessageNotReadableException}（请求体缺失/不可读）→ 400（{@link BusinessCode#VALIDATION_FAILED}）</li>
 *     <li>{@link MaxUploadSizeExceededException}（上传超系统 multipart 上限，业务上限前的最后防线）→ 400
 *         （{@code storage.file_too_large}）</li>
 *     <li>{@link NoResourceFoundException}（未匹配路径）→ 404（{@link BusinessCode#NOT_FOUND}）</li>
 *     <li>{@link RuntimeException} 兜底 → 500（{@link BusinessCode#INTERNAL_ERROR}），
 *         通用消息不泄露内部细节，堆栈仅记录在服务端日志</li>
 * </ul>
 * HTTP 状态码由本类集中设置：业务层与响应体不承载状态码。
 */
@RestControllerAdvice
@Slf4j
@ScaffoldGenerated
public class ExceptionAdviser {

    /**
     * 校验失败响应的固定消息文案。
     */
    private static final String ILLEGAL_ARGS = "illegal args";

    /**
     * 处理业务异常：状态按显式指定 &gt; 业务码默认映射解析，消息与业务码透传。
     * <p>
     * 状态为动态值，使用 {@link ResponseEntity#status} 设置（静态 {@code @ResponseStatus} 无法表达）。
     *
     * @param e 业务异常
     * @return 失败响应（携带业务码与异常消息）
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<HttpResponse<?>> handleBusinessException(BusinessException e) {
        final String businessCode = Objects.requireNonNullElse(e.getBusinessCode(), BusinessCode.INTERNAL_ERROR.code());
        return ResponseEntity.status(HttpStatus.valueOf(e.getHttpStatus()))
                .body(HttpResponse.fail(businessCode, e.getMessage()));
    }

    /**
     * 处理方法参数校验异常：返回 400 + 字段级错误 map。
     *
     * @param e 参数校验异常
     * @return 失败响应（业务码 {@link BusinessCode#VALIDATION_FAILED} + 字段错误 map）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public HttpResponse<?> handleValidationException(MethodArgumentNotValidException e) {
        return validationFailed(collectFieldErrors(e.getBindingResult()));
    }

    /**
     * 处理表单绑定异常：返回 400 + 字段级错误 map。
     *
     * @param e 绑定异常
     * @return 失败响应（业务码 {@link BusinessCode#VALIDATION_FAILED} + 字段错误 map）
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public HttpResponse<?> handleBindException(BindException e) {
        return validationFailed(collectFieldErrors(e.getBindingResult()));
    }

    /**
     * 处理请求体缺失/不可读异常（客户端错误，非认证拦截语义）：返回 400。
     *
     * @param e 请求体异常
     * @return 失败响应（业务码 {@link BusinessCode#VALIDATION_FAILED}）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public HttpResponse<?> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return HttpResponse.fail(BusinessCode.VALIDATION_FAILED.code(), "Request body missing or malformed");
    }

    /**
     * 处理上传超限异常（系统 multipart 上限拦截，发生在 controller 之前）：
     * 返回 400 + 存储域业务码（业务层上限 {@code nona.storage.max-size-bytes} 已在存储实现内判定，
     * 本类为系统兜底防线）。
     *
     * @param e 上传超限异常
     * @return 失败响应（业务码 {@code storage.file_too_large}）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public HttpResponse<?> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return HttpResponse.fail(EcommerceBusinessCode.STORAGE_FILE_TOO_LARGE.code(), "file too large");
    }

    /**
     * 处理未匹配路径异常（Spring MVC 静态资源解析抛出）：返回 404。
     *
     * @param e 未匹配路径异常
     * @return 失败响应（业务码 {@link BusinessCode#NOT_FOUND}）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public HttpResponse<?> handleNoResourceFound(NoResourceFoundException e) {
        return HttpResponse.fail(BusinessCode.NOT_FOUND.code(), "Resource not found");
    }

    /**
     * 兜底处理运行时异常：记录堆栈，返回通用错误消息（不泄露内部细节）。
     *
     * @param e 运行时异常
     * @return 失败响应（业务码 {@link BusinessCode#INTERNAL_ERROR} + 通用消息）
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public HttpResponse<?> handleRuntimeException(RuntimeException e) {
        log.error("unhandled exception : {}", e.getMessage());
        log.error("stack is ", e);
        return HttpResponse.fail(BusinessCode.INTERNAL_ERROR.code(), "Severe internal error. Please retry later.");
    }

    /**
     * 收集字段级校验错误为 map（字段名 → 默认错误消息）。
     *
     * @param bindingResult 校验绑定结果
     * @return 字段错误 map，可能为空
     */
    private Map<String, String> collectFieldErrors(BindingResult bindingResult) {
        final Map<String, String> errors = new HashMap<>();
        bindingResult.getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        return errors;
    }

    /**
     * 构建校验失败响应并记录错误日志。
     *
     * @param errors 字段错误 map
     * @return 校验失败响应体
     */
    private HttpResponse<?> validationFailed(Map<String, String> errors) {
        log.error("{} :{}", ILLEGAL_ARGS, errors);
        return HttpResponse.fail(BusinessCode.VALIDATION_FAILED.code(), ILLEGAL_ARGS, errors);
    }
}
