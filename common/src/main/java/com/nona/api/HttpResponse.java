package com.nona.api;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.exceptions.BusinessCode;

/**
 * 通用 API 响应体。
 * <p>
 * 所有接口统一返回该结构。HTTP 语义下：
 * <ul>
 *     <li>成功响应 {@code code} 为固定成功码 {@value #SUCCESS}</li>
 *     <li>失败响应由 {@code code} 承载业务码（小写点分，如 {@code generic.validation_failed}）</li>
 *     <li>响应体不承载 HTTP 状态码（状态由全局异常处理统一设置）</li>
 * </ul>
 *
 * @param code    成功为固定成功码，失败为业务码
 * @param message 提示信息
 * @param success 是否成功
 * @param data    业务数据（成功为业务数据，失败可为错误详情）
 * @param <T>     数据类型
 * @author nona9961
 */
@ScaffoldGenerated
public record HttpResponse<T>(String code, String message, boolean success, T data) {

    /**
     * 成功响应的固定业务码。
     */
    public static final String SUCCESS = "success";

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return 成功响应体
     */
    public static <T> HttpResponse<T> ok() {
        return new HttpResponse<>(SUCCESS, "success", true, null);
    }

    /**
     * 成功响应（携带数据）。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应体
     */
    public static <T> HttpResponse<T> ok(T data) {
        return new HttpResponse<>(SUCCESS, "success", true, data);
    }

    /**
     * 失败响应（仅业务码 + 消息）。
     *
     * @param businessCode 业务码（小写点分，见 {@link BusinessCode}）
     * @param message      业务错误原因
     * @param <T>          数据类型
     * @return 失败响应体
     */
    public static <T> HttpResponse<T> fail(String businessCode, String message) {
        return new HttpResponse<>(businessCode, message, false, null);
    }

    /**
     * 失败响应（业务码 + 消息 + 错误数据）。
     *
     * @param businessCode 业务码（小写点分，见 {@link BusinessCode}）
     * @param message      业务错误原因
     * @param data         错误详情数据（如字段校验错误 map）
     * @param <T>          数据类型
     * @return 失败响应体
     */
    public static <T> HttpResponse<T> fail(String businessCode, String message, T data) {
        return new HttpResponse<>(businessCode, message, false, data);
    }
}
