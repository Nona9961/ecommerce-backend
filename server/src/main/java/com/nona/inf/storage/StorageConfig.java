package com.nona.inf.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储装配：启用 {@link StorageProperties} 并注册 {@link FileStorage} 防腐层 Bean。
 * <p>
 * Bean 以接口类型暴露，业务/Web 层只依赖 {@link FileStorage}，换实现不动调用方。
 *
 * @author nona9961
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * 注册本地磁盘存储实现（根目录创建/校验在构造器完成，配置非法启动失败）。
     *
     * @param properties 存储配置
     * @return 文件存储防腐层
     */
    @Bean
    FileStorage fileStorage(StorageProperties properties) {
        return new LocalDiskStorage(properties);
    }
}