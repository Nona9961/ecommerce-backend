package com.nona.api;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 通用返回 response
 * <p>
 * 所有接口统一返回该结构：业务状态由 {@code code} 承载（0=成功，500=失败），
 * 而非 HTTP 状态码。
 *
 * @param code    业务状态码
 * @param message 提示信息
 * @param success 是否成功
 * @param data    业务数据
 * @param <T>     数据类型
 */
@ScaffoldGenerated
public record HttpResponse<T>(int code, String message, boolean success, T data) {

    /**
     * 成功状态码
     */
    public static final int SUCCESS_CODE = 0;

    /**
     * 未授权状态码
     */
    public static final int UNAUTHORIZED_CODE = 401;

    /**
     * 失败状态码
     */
    public static final int FAIL_CODE = 500;

    /**
     * 成功响应（无数据）。
     *
     * @return 成功响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> ok() {
        return new HttpResponse<>(SUCCESS_CODE, "success", true, null);
    }

    /**
     * 成功响应（携带数据）。
     *
     * @param data 业务数据
     * @return 成功响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> ok(T data) {
        return new HttpResponse<>(SUCCESS_CODE, "success", true, data);
    }

    /**
     * 失败响应（仅提示信息）。
     *
     * @param message 失败原因，展示给调用方
     * @return 失败响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> fail(String message) {
        return new HttpResponse<>(FAIL_CODE, message, false, null);
    }

    /**
     * 失败响应（携带错误数据）。
     *
     * @param data 错误详情数据（如字段校验错误 map）
     * @return 失败响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> fail(T data) {
        return new HttpResponse<>(FAIL_CODE, "fail", false, data);
    }

    /**
     * 失败响应（提示信息 + 错误数据）。
     *
     * @param message 失败原因，展示给调用方
     * @param data    错误详情数据
     * @return 失败响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> fail(String message, T data) {
        return new HttpResponse<>(FAIL_CODE, message, false, data);
    }

    /**
     * 未授权响应。
     *
     * @return 未授权响应体
     * @param <T> 数据类型
     */
    public static <T> HttpResponse<T> unauthorizedFail() {
        return new HttpResponse<>(UNAUTHORIZED_CODE, "unauthorized", false, null);
    }
}
