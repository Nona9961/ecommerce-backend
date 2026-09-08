package com.nona.inf.persistence.repository.replica;

import com.nona.api.common.PageQuery;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRepository;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRow;
import com.nona.domain.order.entity.SubOrderStatus;
import org.mybatis.dynamic.sql.SortSpecification;
import org.mybatis.dynamic.sql.dsl.WhereDSL;
import org.mybatis.dynamic.sql.select.QueryExpressionDSL;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.util.Buildable;
import org.mybatis.dynamic.sql.util.spring.NamedParameterJdbcTemplateExtensions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import static com.nona.inf.persistence.repository.replica.PlatformLogisticsViewDynamicSqlSupport.shop;
import static com.nona.inf.persistence.repository.replica.PlatformLogisticsViewDynamicSqlSupport.subOrder;
import static com.nona.inf.persistence.repository.replica.PlatformLogisticsViewDynamicSqlSupport.waybill;
import static org.mybatis.dynamic.sql.SqlBuilder.countFrom;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualToWhenPresent;
import static org.mybatis.dynamic.sql.SqlBuilder.select;
import static org.mybatis.dynamic.sql.SqlBuilder.where;

/**
 * 平台物流视图读模型仓储实现（replica 通道，P5.1 平台监督列表数据面）。
 * <p>
 * 只读三张镜像表（waybill / sub_order / shop，CDC 白名单在案——镜像
 * 链路存在性由验收前同步核对兜底）：行单元 = 子单
 * （sub_order LEFT JOIN waybill ON waybill.sub_order_id = sub_order.id
 * LEFT JOIN shop ON shop.id = sub_order.shop_id），跨店铺全集
 * （PG 侧无租户过滤，平台全局命中）。
 * <p>
 * 实现纪律：
 * <ul>
 *     <li>仅持 {@code replicaNamedParameterJdbcTemplate}（限名装配，
 *         无运行时路由）；无事务管理器、无任何写路径——镜像表只读
 *         语义由结构保证；</li>
 *     <li>动态 SQL 与既有搜索读模型仓储同型（MyBatis Dynamic SQL
 *         support 类承载表/列引用）：筛选条件 = 店铺等值 + 子单状态
 *         等值（状态列按枚举名文字存储）；排序固定子单创建时间倒序
 *         （三表同名列经排序列别名限定子单表，见 {@link #createTimeDesc()}）；
 *         limit/offset 由 {@link PageQuery} 承载；count 与 search 共用
 *         条件构建（总口径一致）；</li>
 *     <li>列投影与行记录一一对应（列名 = 源表 snake_case 列名；id/status
 *         多表同名列经 select 别名区分标签；状态列按枚举名文字映射；
 *         时间列 TIMESTAMP → UTC 时刻）。</li>
 * </ul>
 *
 * @author nona9961
 */
@Repository
public class PlatformLogisticsViewRepositoryImpl implements PlatformLogisticsViewRepository {

    /**
     * 子单表别名（投影自动限定与排序列限定共用）。
     */
    private static final String SUB_ORDER_ALIAS = "so";

    /**
     * 运单表别名（投影自动限定）。
     */
    private static final String WAYBILL_ALIAS = "wb";

    /**
     * 店铺表别名（投影自动限定）。
     */
    private static final String SHOP_ALIAS = "sh";

    /**
     * 当前页行投影映射（列标签与 select 投影别名一一对应：子单行维度
     * 必填列直接取值；运单附注列/超时截止列 LEFT JOIN 未命中或空记录
     * 时为空）。
     */
    private static final RowMapper<PlatformLogisticsViewRow> ROW_MAPPER = (rs, rowNum) -> {
        final Timestamp timeoutAt = rs.getTimestamp("timeout_at");
        final String waybillStatus = rs.getString("waybill_status");
        return new PlatformLogisticsViewRow(
                rs.getLong("sub_order_id"),
                rs.getString("sub_order_no"),
                rs.getLong("master_order_id"),
                rs.getLong("shop_id"),
                rs.getString("name"),
                SubOrderStatus.valueOf(rs.getString("sub_order_status")),
                nullableLong(rs, "waybill_id"),
                rs.getString("company"),
                rs.getString("tracking_no"),
                waybillStatus == null ? null : WaybillStatus.valueOf(waybillStatus),
                timeoutAt == null ? null : timeoutAt.toInstant());
    };

    /**
     * 搜索读模型扩展执行器（绑定 replica 命名参数模板；
     * 渲染策略 SPRING_NAMED_PARAMETER 由扩展类统一承担）。
     */
    private final NamedParameterJdbcTemplateExtensions replicaExtensions;

    /**
     * 构造实现（replica 模板限名注入：主库模板由主库通道持有，本实现
     * 只经名字限定拿到 PG 镜像库模板——静态装配白名单纪律）。
     *
     * @param replicaNamedParameterJdbcTemplate replica 命名参数模板
     */
    public PlatformLogisticsViewRepositoryImpl(
            @Qualifier("replicaNamedParameterJdbcTemplate")
            NamedParameterJdbcTemplate replicaNamedParameterJdbcTemplate) {
        this.replicaExtensions = new NamedParameterJdbcTemplateExtensions(replicaNamedParameterJdbcTemplate);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 接线语义：子单 + 运单 + 店铺三表投影查询，筛选条件（店铺等值/
     * 状态等值）与排序（子单创建时间倒序）与分页切片收敛本方法。
     */
    @Override
    public List<PlatformLogisticsViewRow> search(PlatformLogisticsViewFilter filter, PageQuery page) {
        return replicaExtensions.selectList(pageStatement(filter, page), ROW_MAPPER);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 接线语义：与 {@link #search} 同条件构建的计数查询（同口径，
     * 无排序/分页；LEFT JOIN 不引入行膨胀——一子单至多一条运单）。
     */
    @Override
    public long count(PlatformLogisticsViewFilter filter) {
        return replicaExtensions.count(countStatement(filter));
    }

    /**
     * 当前页查询语句构建（条件 + 固定排序 + 分页切片）。
     *
     * @param filter 筛选条件（店铺等值/子单状态等值；null 字段 = 不过滤）
     * @param page   分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 可渲染的分页查询语句（执行扩展器消费）
     */
    private static Buildable<SelectModel> pageStatement(PlatformLogisticsViewFilter filter, PageQuery page) {
        final QueryExpressionDSL<SelectModel>.JoinSpecificationFinisher base = baseQuery();
        final WhereDSL where = buildWhere(filter);
        if (where != null) {
            return base.applyWhere(where.toWhereApplier())
                    .orderBy(createTimeDesc())
                    .limit(page.pageSize())
                    .offset(page.offset());
        }
        return base.orderBy(createTimeDesc())
                .limit(page.pageSize())
                .offset(page.offset());
    }

    /**
     * 总数统计语句构建（与 {@link #pageStatement} 同 join 同条件）。
     *
     * @param filter 筛选条件（null 字段 = 不过滤）
     * @return 可渲染的 count 语句（执行扩展器消费）
     */
    private static Buildable<SelectModel> countStatement(PlatformLogisticsViewFilter filter) {
        final var countBase = countFrom(subOrder, SUB_ORDER_ALIAS)
                .leftJoin(waybill, WAYBILL_ALIAS).on(waybill.subOrderId, isEqualTo(subOrder.id))
                .leftJoin(shop, SHOP_ALIAS).on(shop.id, isEqualTo(subOrder.shopId));
        final WhereDSL where = buildWhere(filter);
        if (where != null) {
            return countBase.applyWhere(where.toWhereApplier());
        }
        return countBase;
    }

    /**
     * 三表投影查询基底（select 列 + LEFT JOIN 链）。
     * <p>
     * 多表同名列（id / status）经 select 别名唯一化列标签（渲染为
     * {@code wb.id as waybill_id} 形态）——ResultSet 按标签取值避免
     * 同名列歧义；未发货子单经 LEFT JOIN 未命中自然取空。
     *
     * @return 带 join 的查询 DSL（条件/排序/分页由调用方追加）
     */
    private static QueryExpressionDSL<SelectModel>.JoinSpecificationFinisher baseQuery() {
        return select(
                subOrder.id.as("sub_order_id"), subOrder.subOrderNo, subOrder.masterOrderId,
                subOrder.shopId, shop.name, subOrder.status.as("sub_order_status"),
                waybill.id.as("waybill_id"), waybill.company, waybill.trackingNo,
                waybill.status.as("waybill_status"), subOrder.timeoutAt)
                .from(subOrder, SUB_ORDER_ALIAS)
                .leftJoin(waybill, WAYBILL_ALIAS).on(waybill.subOrderId, isEqualTo(subOrder.id))
                .leftJoin(shop, SHOP_ALIAS).on(shop.id, isEqualTo(subOrder.shopId));
    }

    /**
     * 动态条件构建（search 与 count 共用，口径一致）。
     * <p>
     * 全部条件为 when-present 形态：店铺 ID 等值 / 子单状态枚举名文字
     * 等值（null 即省略）。无任何条件时返回 null——调用方跳过 where
     * 子句（跨店铺全集全量语义）。
     *
     * @param filter 筛选条件（null 或双 null 字段 = 不过滤）
     * @return 条件 DSL；无生效条件时返回 null
     */
    private static WhereDSL buildWhere(PlatformLogisticsViewFilter filter) {
        if (filter == null || (filter.shopId() == null && filter.status() == null)) {
            return null;
        }
        return where(subOrder.shopId, isEqualToWhenPresent(filter.shopId()))
                .and(subOrder.status, isEqualToWhenPresent(statusName(filter)));
    }

    /**
     * 固定排序列（子单创建时间倒序，最新下单在前）。
     * <p>
     * 实现说明：排序列渲染只输出列名/别名（不输出表限定），三表同名列
     * 时以别名携带表限定（{@code so.create_time}）消除歧义。
     *
     * @return 子单创建时间倒序排序列
     */
    private static SortSpecification createTimeDesc() {
        return subOrder.createTime.as(SUB_ORDER_ALIAS + ".create_time").descending();
    }

    /**
     * 子单状态筛选值 → 枚举名文字（与状态列存储形态一致）。
     *
     * @param filter 筛选条件
     * @return 状态枚举名；未筛选返回 null
     */
    private static String statusName(PlatformLogisticsViewFilter filter) {
        return filter.status() == null ? null : filter.status().name();
    }

    /**
     * 可空 BIGINT 列读取（LEFT JOIN 未命中按空处理，不落默认值）。
     *
     * @param rs          结果集
     * @param columnLabel 列标签
     * @return 列值；SQL NULL 返回 null
     * @throws SQLException JDBC 读取失败
     */
    private static Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        final long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }
}