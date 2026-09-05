package com.nona.inf.replica;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * replica 读库装配（WU-41 一期：PG 读库双数据源基建，TD-08）。
 * <p>
 * 装配形态决策：<b>replica 以 JdbcTemplate 直连形态暴露</b>，不注册
 * DataSource bean、不建第二套 EntityManagerFactory：
 * <ul>
 *     <li>视图无实体映射：product_search_view 是 PG 内建视图（部署位
 *         DDL），无 JPA 实体/PO 可挂——EMF 空转且引入实体扫描/租户
 *         resolver 双套配置的复杂度；</li>
 *     <li>ILIKE + 动态条件 + 白名单排序的分页 SQL 手写直白，RowMapper
 *         映射到 {@code ProductCard} 简洁；</li>
 *     <li>不注册 DataSource bean 的关键约束①：Spring Boot 的
 *         DataSourceAutoConfiguration 以
 *         {@code @ConditionalOnMissingBean(DataSource.class)} 决定是否
 *         自动装配主数据源——注册第二个 DataSource bean 会顶掉主库自动
 *         装配（主库 JPA/事务全部失效）；本配置只注册
 *         {@code replicaJdbcTemplate}（JdbcOperations 类型，与
 *         JdbcTemplateAutoConfiguration 的
 *         {@code @ConditionalOnMissingBean(JdbcOperations)} 互斥，Boot
 *         侧不再生成默认 JdbcTemplate）；</li>
 *     <li>不注册 DataSourceProperties bean 的关键约束②：Boot 主数据源
 *         自动装配 {@code DataSourceConfiguration.dataSource(DataSourceProperties)}
 *         按类型注入——注册第二个 DataSourceProperties bean 会触发注入
 *         歧义（实测 APPLICATION FAILED TO START）；故 replica 属性经
 *         {@link Binder} 从环境读取，不产生任何额外 bean；</li>
 *     <li>主库 jdbcTemplate 回填：JdbcTemplateAutoConfiguration 在容器已
 *         有 JdbcOperations 实例时跳过默认生成——若不回填，既有测试/代码
 *         注入无限定的 {@link JdbcTemplate} 时会命中 replica 模板（实测
 *         既有测试被带偏查错库）；故显式注册主库模板（@Primary，行为
 *         等价 Boot 默认），replica 模板以名字限定隔离；</li>
 *     <li>只读纪律：本数据源不暴露任何写路径（无事务管理器、无
 *         Repository 直连，访问收敛在 search 读模型仓储），镜像表只读
 *         语义由结构保证（§3.3：PG 镜像表只服务读场景）。</li>
 * </ul>
 * 配置位：{@code replica.datasource.*}（dev/test 指向 H2 内存模拟库跑
 * 服务语义；prod 指向 PG 读库，见部署位联调说明）。
 *
 * @author nona9961
 */
@Configuration
public class ReplicaDataSourceConfig {

    /**
     * 主库 JdbcTemplate（回填 Boot 默认行为：见类注释「主库 jdbcTemplate
     * 回填」；无限定注入点默认拿到本模板）。
     *
     * @param primaryDataSource 主库数据源（Boot 自动装配，唯一 DataSource bean）
     * @return 主库 JdbcTemplate
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource primaryDataSource) {
        return new JdbcTemplate(primaryDataSource);
    }

    /**
     * replica JdbcTemplate（search 读模型仓储唯一数据通道）。
     *
     * @param environment Spring 环境（replica.datasource.* 属性源）
     * @return 绑定 replica 数据源的 JdbcTemplate
     */
    @Bean("replicaJdbcTemplate")
    public JdbcTemplate replicaJdbcTemplate(Environment environment) {
        DataSourceProperties properties = Binder.get(environment)
                .bind("replica.datasource", DataSourceProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "缺少 replica.datasource 配置（WU-41 replica 读库）："
                                + "请在当前 profile 配置 replica.datasource.url/username/password"));
        DataSource dataSource = properties.initializeDataSourceBuilder().build();
        return new JdbcTemplate(dataSource);
    }
}