package com.nona.inf.replica;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * replica 读库装配（PG 读库双数据源基建）。
 * <p>
 * 装配形态决策：<b>replica 以 JdbcTemplate 直连形态暴露</b>，不注册
 * DataSource bean、不建第二套 EntityManagerFactory：
 * <ul>
 *     <li>视图无实体映射：product_search_view 是 PG 内建视图（部署位
 *         DDL），无 JPA 实体/PO 可挂——EMF 空转且引入实体扫描/租户
 *         resolver 双套配置的复杂度；</li>
 *     <li>SQL 由 MyBatis Dynamic SQL 类型安全生成（官方 support 类 +
 *         SPRING_NAMED_PARAMETER 渲染），RowMapper 映射到
 *         {@code ProductCard} 简洁；</li>
 *     <li>不注册 DataSource bean 的关键约束①：Spring Boot 的
 *         DataSourceAutoConfiguration 以
 *         {@code @ConditionalOnMissingBean(DataSource.class)} 决定是否
 *         自动装配主数据源——注册第二个 DataSource bean 会顶掉主库自动
 *         装配（主库 JPA/事务全部失效）；本配置只注册数据访问模板
 *         （replicaJdbcTemplate / replicaNamedParameterJdbcTemplate，
 *         与 Boot 模板自动装配的
 *         {@code @ConditionalOnMissingBean(JdbcOperations)} /
 *         {@code @ConditionalOnMissingBean(NamedParameterJdbcTemplate)}
 *         互斥，Boot 侧不再生成默认模板）；</li>
 *     <li>不注册 DataSourceProperties bean 的关键约束②：Boot 主数据源
 *         自动装配 {@code DataSourceConfiguration.dataSource(DataSourceProperties)}
 *         按类型注入——注册第二个 DataSourceProperties bean 会触发注入
 *         歧义（实测 APPLICATION FAILED TO START）；故 replica 属性经
 *         {@link Binder} 从环境读取，不产生任何额外 bean；</li>
 *     <li>主库模板回填（JdbcOperations 与 NamedParameterJdbcTemplate
 *         双类型同款纪律）：Boot 模板自动装配在容器已有同类型实例时
 *         跳过默认生成——若不回填，既有测试/代码注入无限定的模板时
 *         会命中 replica 实例（实测既有测试被带偏查错库）；故显式注册
 *         主库模板（@Primary，行为等价 Boot 默认），replica 模板以名字
 *         限定隔离；</li>
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
     * 主库 NamedParameterJdbcTemplate（回填 Boot 默认行为：本配置注册
     * NamedParameterJdbcTemplate 类型 bean 后，Boot 的
     * {@code @ConditionalOnMissingBean(NamedParameterJdbcTemplate)} 自动
     * 装配被压制——与「主库 jdbcTemplate 回填」同款纪律，无限定注入点
     * 默认拿到主库模板，行为等价 Boot 默认）。
     *
     * @param jdbcTemplate 主库 JdbcTemplate（{@link #jdbcTemplate}，名字限定）
     * @return 主库命名参数模板
     */
    @Bean
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * replica NamedParameterJdbcTemplate（search 读模型仓储执行通道；
     * MyBatis Dynamic SQL 的 SPRING_NAMED_PARAMETER 渲染需要命名参数
     * 模板执行，见 ProductSearchViewRepositoryImpl）。
     *
     * @param replicaJdbcTemplate replica JdbcTemplate（{@link #replicaJdbcTemplate}）
     * @return 绑定 replica 数据源的命名参数模板
     */
    @Bean("replicaNamedParameterJdbcTemplate")
    public NamedParameterJdbcTemplate replicaNamedParameterJdbcTemplate(
            @Qualifier("replicaJdbcTemplate") JdbcTemplate replicaJdbcTemplate) {
        return new NamedParameterJdbcTemplate(replicaJdbcTemplate);
    }

    /**
     * replica JdbcTemplate（search 读模型仓储限名注入通道；replica 命名
     * 参数模板的底层模板）。
     *
     * @param environment Spring 环境（replica.datasource.* 属性源）
     * @return 绑定 replica 数据源的 JdbcTemplate
     */
    @Bean("replicaJdbcTemplate")
    public JdbcTemplate replicaJdbcTemplate(Environment environment) {
        DataSourceProperties properties = Binder.get(environment)
                .bind("replica.datasource", DataSourceProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "缺少 replica.datasource 配置（replica 读库）："
                                + "请在当前 profile 配置 replica.datasource.url/username/password"));
        DataSource dataSource = properties.initializeDataSourceBuilder().build();
        return new JdbcTemplate(dataSource);
    }
}