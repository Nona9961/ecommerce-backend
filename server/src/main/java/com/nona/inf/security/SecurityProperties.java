package com.nona.inf.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全链路配置：JWT 密钥/有效期与用户上下文缓存 TTL。
 * <p>
 * 密钥通过环境变量注入（生产禁止落入配置文件），本地开发由 dev profile 提供默认值。
 *
 * @param jwtSecret                  JWT HS256 签名密钥（至少 32 字节）
 * @param jwtTtlSeconds              JWT 有效期（秒），当前 2 小时
 * @param userContextCacheTtlSeconds 用户上下文缓存兜底 TTL（秒），当前 1 小时
 * @author nona9961
 */
@ConfigurationProperties(prefix = "nona.security")
public record SecurityProperties(
        String jwtSecret,
        long jwtTtlSeconds,
        long userContextCacheTtlSeconds
) {
}