package com.nona.inf.storage;

import java.io.InputStream;

/**
 * 文件存储防腐层（inf.storage）：上传/读取/删除对象存储形态的二进制内容。
 * <p>
 * 业务域不感知存储实现（本地磁盘/对象存储均可），URL 统一形态
 * {@code /files/{objectKey}} 由 web 层拼接；未来更换实现仅替换本接口的实现类。
 * objectKey 由实现生成（不经用户输入），字符集受限（拒绝 {@code ../}、绝对路径）。
 *
 * @author nona9961
 */
public interface FileStorage {

    /**
     * 上传内容：校验 contentType 白名单与大小上限后落盘。
     *
     * @param stream      内容流（调用方负责关闭）
     * @param contentType MIME 类型（如 image/png）
     * @return 生成的 objectKey（嵌入日期目录与类型扩展名）
     */
    String put(InputStream stream, String contentType);

    /**
     * 读取内容。
     *
     * @param objectKey 对象键
     * @return 内容流（调用方负责关闭）
     */
    InputStream get(String objectKey);

    /**
     * 删除内容（幂等：不存在视为成功）。
     *
     * @param objectKey 对象键
     */
    void delete(String objectKey);
}