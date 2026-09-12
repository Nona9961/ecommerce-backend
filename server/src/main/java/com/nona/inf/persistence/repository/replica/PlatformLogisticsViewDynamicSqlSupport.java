package com.nona.inf.persistence.repository.replica;

import org.mybatis.dynamic.sql.AliasableSqlTable;
import org.mybatis.dynamic.sql.SqlColumn;

import java.sql.JDBCType;
import java.sql.Timestamp;

/**
 * 平台物流视图查询支撑（replica 镜像三表列定义；MyBatis Dynamic SQL
 * 官方 support 形态：final 类 + AliasableSqlTable 子类 + 静态表实例
 * 导出）。
 * <p>
 * 三张镜像表（waybill / sub_order / shop）的列集与本查询实际用途对齐
 * （按 PlatformLogisticsViewRow / Filter / 固定排序定）：子单表承载
 * 行主维度（含超时截止列与创建时间排序列）；运单表承载物流凭据附注
 * （未发货子单 LEFT JOIN 未命中即空）；店铺表承载店名投影。
 * <p>
 * 多表同名列（id / status）经查询侧 select 别名区分（渲染与行映射见
 * {@link PlatformLogisticsViewRepositoryImpl}），本类只定义裸列；
 * JDBCType 与镜像表列型对齐（状态列 VARCHAR = 枚举名文字存储；时间列
 * TIMESTAMP = UTC 墙钟）。
 *
 * @author nona9961
 */
public final class PlatformLogisticsViewDynamicSqlSupport {

    /**
     * 子单镜像表对象（行单元 = 子单，查询主表）。
     */
    public static final SubOrderTable subOrder = new SubOrderTable();

    /**
     * 运单镜像表对象（子单的物流凭据附注表，LEFT JOIN 关联）。
     */
    public static final WaybillTable waybill = new WaybillTable();

    /**
     * 店铺镜像表对象（店铺名投影表，LEFT JOIN 关联）。
     */
    public static final ShopTable shop = new ShopTable();

    /**
     * 工具类私有构造（纯静态支撑）。
     */
    private PlatformLogisticsViewDynamicSqlSupport() {
    }

    /**
     * sub_order 镜像表定义（列与主库子单主表对齐；create_time 为固定
     * 排序引用列，不参与投影）。
     */
    public static final class SubOrderTable extends AliasableSqlTable<SubOrderTable> {

        /**
         * 子单主键列（行单元标识，BIGINT）。
         */
        public final SqlColumn<Long> id = column("id", JDBCType.BIGINT);

        /**
         * 子单业务单号列（VARCHAR）。
         */
        public final SqlColumn<String> subOrderNo = column("sub_order_no", JDBCType.VARCHAR);

        /**
         * 归属主单 ID 列（BIGINT）。
         */
        public final SqlColumn<Long> masterOrderId = column("master_order_id", JDBCType.BIGINT);

        /**
         * 归属店铺 ID 列（筛选/店铺 JOIN 键，BIGINT）。
         */
        public final SqlColumn<Long> shopId = column("shop_id", JDBCType.BIGINT);

        /**
         * 子单履约状态列（VARCHAR，枚举名文字存储）。
         */
        public final SqlColumn<String> status = column("status", JDBCType.VARCHAR);

        /**
         * 发货超时截止时间列（TIMESTAMP，UTC 墙钟；无 deadline 记录为空）。
         */
        public final SqlColumn<Timestamp> timeoutAt = column("timeout_at", JDBCType.TIMESTAMP);

        /**
         * 子单创建时间列（TIMESTAMP；固定排序「最新下单在前」引用列）。
         */
        public final SqlColumn<Timestamp> createTime = column("create_time", JDBCType.TIMESTAMP);

        /**
         * 子单表构造（表名 = sub_order，无 schema 前缀）。
         */
        public SubOrderTable() {
            super("sub_order", SubOrderTable::new);
        }
    }

    /**
     * waybill 镜像表定义（列与主库运单主表对齐）。
     */
    public static final class WaybillTable extends AliasableSqlTable<WaybillTable> {

        /**
         * 运单主键列（物流附注投影，BIGINT；未发货为空）。
         */
        public final SqlColumn<Long> id = column("id", JDBCType.BIGINT);

        /**
         * 归属子单 ID 列（LEFT JOIN 键，BIGINT）。
         */
        public final SqlColumn<Long> subOrderId = column("sub_order_id", JDBCType.BIGINT);

        /**
         * 承运公司列（VARCHAR；未发货为空）。
         */
        public final SqlColumn<String> company = column("company", JDBCType.VARCHAR);

        /**
         * 运单号列（VARCHAR；未发货为空）。
         */
        public final SqlColumn<String> trackingNo = column("tracking_no", JDBCType.VARCHAR);

        /**
         * 运单物流状态列（VARCHAR，枚举名文字存储；未发货为空）。
         */
        public final SqlColumn<String> status = column("status", JDBCType.VARCHAR);

        /**
         * 运单表构造（表名 = waybill，无 schema 前缀）。
         */
        public WaybillTable() {
            super("waybill", WaybillTable::new);
        }
    }

    /**
     * shop 镜像表定义（列与主库店铺表对齐）。
     */
    public static final class ShopTable extends AliasableSqlTable<ShopTable> {

        /**
         * 店铺主键列（LEFT JOIN 键，BIGINT）。
         */
        public final SqlColumn<Long> id = column("id", JDBCType.BIGINT);

        /**
         * 店铺名列（店名投影，VARCHAR）。
         */
        public final SqlColumn<String> name = column("name", JDBCType.VARCHAR);

        /**
         * 店铺表构造（表名 = shop，无 schema 前缀）。
         */
        public ShopTable() {
            super("shop", ShopTable::new);
        }
    }
}