package com.nona.api.common;

import com.nona.api.HttpResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储契约：上传 / 读取 / 删除（三端通用，不区分门户；一期评价图二期同用）。
 * <p>
 * 上传需登录（安全链任意已认证角色），读取公开（商品图在买家端展示，
 * URL 统一形态 {@code /files/{objectKey}}，业务表只存该 URL 字符串）；
 * 删除一期登录即可（商品图删除的归属校验随商品域细化）。
 *
 * @author nona9961
 */
public interface FileApi {

    /**
     * 上传文件（multipart 直传）：contentType 白名单 + 大小上限校验后落盘。
     *
     * @param file 上传文件（part 名固定为 file）
     * @return 携带统一 URL 形态（{@code /files/{objectKey}}）的响应
     */
    HttpResponse<FileUploadResponse> upload(MultipartFile file);

    /**
     * 读取文件（公开）：按 objectKey 返回原始字节流与正确 Content-Type。
     *
     * @param objectKey 对象键（URL 中 {@code /files/} 之后的部分）
     * @return 文件流响应；不存在返回 404 业务码
     */
    ResponseEntity<InputStreamResource> download(String objectKey);

    /**
     * 删除文件（需登录）：幂等语义，不存在视为成功。
     *
     * @param objectKey 对象键
     * @return 成功响应
     */
    HttpResponse<Void> delete(String objectKey);
}