package com.nona.web;

import com.nona.api.HttpResponse;
import com.nona.api.common.FileApi;
import com.nona.api.common.FileUploadResponse;
import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;
import com.nona.inf.storage.FileStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储 REST 控制器（三端通用，不区分门户）。
 * <p>
 * 控制器保持薄壳：委托 {@link FileStorage} 防腐层，不承载业务逻辑；
 * 上传需登录（安全链任意已认证角色），读取公开（商品图买家端展示），
 * 删除登录即可（一期不做归属校验，随商品域细化）。URL 统一形态
 * {@code /files/{objectKey}}，业务表只存该字符串。
 *
 * @author nona9961
 */
@RestController
public class FileApiController implements FileApi {

    /**
     * 文件存储防腐层
     */
    private final FileStorage fileStorage;

    /**
     * 构造文件控制器。
     *
     * @param fileStorage 文件存储防腐层
     */
    public FileApiController(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HttpResponse<FileUploadResponse> upload(@RequestPart("file") MultipartFile file) {
        final String key = fileStorage.put(readStream(file), file.getContentType());
        return HttpResponse.ok(new FileUploadResponse("/files/" + key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/files/{*objectKey}")
    public ResponseEntity<InputStreamResource> download(@PathVariable("objectKey") String objectKey) {
        final String key = stripLeadingSlash(objectKey);
        final InputStream stream = fileStorage.get(key);
        final MediaType mediaType = MediaTypeFactory.getMediaType(key)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(mediaType).body(new InputStreamResource(stream));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/files/{*objectKey}")
    public HttpResponse<Void> delete(@PathVariable("objectKey") String objectKey) {
        fileStorage.delete(stripLeadingSlash(objectKey));
        return HttpResponse.ok();
    }

    /**
     * 规范化 URL 捕获值：{@code {*objectKey}} catch-all 捕获的变量带框架前导分隔符
     * （如 {@code /2026/08/18/1.png}），剥除后交给防腐层做严格的 objectKey 白名单校验
     * （绝对路径/穿越仍是存储层拒绝语义，本处不削弱防线）。
     *
     * @param captured 路径变量捕获值
     * @return 无前导斜杠的 objectKey
     */
    private String stripLeadingSlash(String captured) {
        return captured.startsWith("/") ? captured.substring(1) : captured;
    }

    /**
     * 打开上传内容流：IO 失败翻译为业务异常（资源打开属机械工作，controller 不承载业务逻辑）。
     *
     * @param file 上传文件
     * @return 内容流
     */
    private InputStream readStream(MultipartFile file) {
        try {
            return file.getInputStream();
        }
        catch (IOException e) {
            throw new BusinessException(BusinessCode.INTERNAL_ERROR.code(), "读取上传内容失败");
        }
    }
}