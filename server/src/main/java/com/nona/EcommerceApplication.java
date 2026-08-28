package com.nona;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 电商平台后端应用入口：Spring Boot 启动类。
 * <p>
 * 模块化单体承载 12 个限界上下文（core: catalog/inventory/order；com: identity/payment/search；
 * s: logistics/marketing/review/settlement/notification/analytics），域间以包边界 + 架构约束隔离，
 * 域间协作只经 application 层用例编排与 ports(ACL) 接口。
 * <p>
 * JPA 仓库限定扫描 {@code com.nona.inf.persistence.repository.jpa} 包。
 *
 * @author nona9961
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.nona.inf.persistence.repository.jpa")
public class EcommerceApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}
