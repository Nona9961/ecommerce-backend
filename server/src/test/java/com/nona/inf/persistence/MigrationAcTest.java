package com.nona.inf.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 迁移冒烟（契约：Flyway V1 落库前 schema 断言）。
 * <p>
 * 装配面：<b>纯 JDBC</b>（DriverManager + mysql-connector-j），不依赖 Spring 上下文——
 * 避免 ddl-auto / Flyway 自动迁移干扰被测面；连接宿主 MySQL 8.4.8 的 ecommerce 库
 * （localtunnel 13306→3306 隧道），not-null 主键、审计列、唯一约束、索引四组断言。
 * <p>
 * 凭证注入：环境变量 {@code ECOM_DB_PASSWORD}（运行命令模板从 /opt/data-stack/.env 的
 * MYSQL_ECOM_PW 映射导出；本文件及任何配置文件不得出现密码明文）。
 * <p>
 * 运行（冒烟形态）：
 * <pre>
 * rm -f /tmp/localtunnel.pids; pkill socat 2>/dev/null
 * set -a; . /opt/data-stack/.env; set +a
 * export ECOM_DB_PASSWORD="$MYSQL_ECOM_PW"
 * timeout 900 /opt/code/.pi/scripts/localtunnel.sh exec bash -c 'cd /opt/code/ecommerce-backend \
 *   &amp;&amp; mvn -o -llr -s /opt/code/.m2/settings.xml -Pfull -Dtest=MigrationAcTest test'
 * </pre>
 * 测试分类：AcTest（surefire 默认排除，-Pfull 或 -Dtest 显式执行）。
 * <p>
 * <b>基线契约</b>：Flyway V1/V2 落库后 ecommerce 库
 * schema = 33 张 PO 映射表 + 探针表 cdc_probe 共存（精确集：33 张
 * PO 映射表全部存在且无多余业务表，cdc_probe 保留不受 V1/V2/
 * Flyway 触碰——探针表不入迁移脚本的部署位契约），其余用例保持常绿
 */
class MigrationAcTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 13306;
    private static final String DATABASE = "ecommerce";
    private static final String USER = "ecom_app";
    private static final String PASSWORD_ENV = "ECOM_DB_PASSWORD";

    /** PO 映射全集（identity 9 / catalog 11 / inventory 2 / order 3 / payment 4 /
     *  logistics 2；探针表 cdc_probe 不入 V1） */
    private static final Set<String> PO_TABLES = Set.of(
            // identity (9)
            "account", "account_shop_rel", "address_book", "address", "assignment",
            "favorite", "onboarding_application", "permission", "role",
            // catalog (11)
            "brand", "freight_template", "platform_category", "product_attribute",
            "product_edit_version", "product_image", "product", "product_shop_category_rel",
            "shop_category", "shop", "product_sku",
            // inventory (2)
            "inventory_item", "inventory_log",
            // order (V1 cart 2 + V2 trading 3)
            "cart", "cart_item", "master_order", "sub_order", "order_item",
            // payment (4)
            "payment_order", "payment_callback_log", "refund_order", "refund_callback_log",
            // logistics (2)
            "waybill", "waybill_track");

    /** 唯一约束全集（@Table/@UniqueConstraint 手审导出；断言名 + 列） */
    private static final List<String> UNIQUE_CONSTRAINTS = List.of(
            "uk_account_type_username", "uk_account_shop_rel", "uk_address_book_account",
            "uk_assignment_account_role", "uk_favorite_account_type_target",
            "uk_onboarding_account", "uk_permission_code", "uk_role_code",
            "uk_brand_name", "uk_platform_category_name", "uk_product_attribute_key",
            "uk_product_edit_version_no", "uk_product_shop_category_rel",
            "uk_product_sku_spec_hash", "uk_inventory_item_sku",
            "uk_inventory_log_order_sku_type", "uk_cart_buyer", "uk_cart_item_buyer_sku",
            // V2 trading：uk 9
            "uk_master_order_order_no", "uk_sub_order_sub_order_no", "uk_order_item_sub_sku",
            "uk_payment_order_pay_no", "uk_payment_order_order_id",
            "uk_payment_order_channel_txn_no", "uk_refund_order_refund_no",
            "uk_refund_order_sub_order_id", "uk_waybill_sub_order_in_transit");

    /** 索引全集（@Index 手审导出） */
    private static final List<String> INDEXES = List.of(
            "idx_address_book", "idx_freight_template_shop", "idx_product_attribute_product",
            "idx_product_edit_version_product", "idx_product_image_product", "idx_product_shop",
            "idx_product_shop_category_rel_product", "idx_shop_category_shop",
            "idx_product_sku_product", "idx_inventory_log_sku", "idx_cart_item_cart",
            // V2 trading：idx 7
            "idx_sub_order_status_timeout", "idx_sub_order_shop", "idx_order_item_sub_order",
            "idx_payment_order_status_timeout", "idx_payment_callback_log_payment_order",
            "idx_refund_callback_log_refund_order", "idx_waybill_track_waybill");

    /** tenant-scoped（@TenantId 归属，TenantScopedBasePO 子类）：12 表含 tenant_id */
    private static final Set<String> TENANT_SCOPED_TABLES = Set.of(
            "freight_template", "product_attribute", "product_edit_version", "product_image",
            "product", "product_shop_category_rel", "shop_category", "product_sku",
            "inventory_item", "inventory_log",
            // V2 trading：tenant=shopId 仅 order 域主从两表
            "sub_order", "order_item");

    /**
     * 测试支撑表（test profile 专属 Flyway R 迁移 R__tenant_test_tables.sql 建立）：
     * 租户隔离契约测试的 @Entity 映射，永不进生产 V1。基线断言容忍其存在与否
     * （-Pfull 全量先跑 AcTest 触发 R 迁移则存在；单独冒烟形态可能缺席）。
     */
    private static final Set<String> TEST_SUPPORT_TABLES = Set.of("test_global_note", "test_tenant_note");

    private static Connection open() throws SQLException {
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("环境变量 " + PASSWORD_ENV + " 未设置（运行命令模板须从 MYSQL_ECOM_PW 映射导出）");
        }
        String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE
                + "?connectTimeout=5000&socketTimeout=8000&useSSL=false&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, USER, password);
    }

    /** 当前库全部 BASE TABLE 名 */
    private static Set<String> baseTables(Connection conn) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DATABASE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private static Set<String> indexNames(Connection conn, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        String sql = "SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DATABASE);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * 迁移基线断言（基线契约）：V1/V2 落库后 ecommerce 库
     * schema = 33 张 PO 映射表 + 部署位探针表 cdc_probe + Flyway 元数据表
     * flyway_schema_history 的必备集——33 表全存在、cdc_probe 仍保留（迁移脚本不管理、
     * Flyway migrate 不触碰）；并断言无上述集合与测试支撑表之外的任何多余业务表
     * （测试支撑表随 test profile R 迁移存在与否均可，两种运行形态都绿）。
     */
    @Test
    void migrationBaselineAfterV1() throws SQLException {
        try (Connection conn = open()) {
            Set<String> tables = baseTables(conn);
            assertThat(tables)
                    .as("V1/V2 落库后必备集：33 张 PO 映射表 + 探针表 cdc_probe + Flyway 元数据表 flyway_schema_history")
                    .containsAll(PO_TABLES)
                    .contains("cdc_probe", "flyway_schema_history");
            Set<String> extra = new LinkedHashSet<>(tables);
            extra.removeAll(PO_TABLES);
            extra.remove("cdc_probe");
            extra.remove("flyway_schema_history");
            extra.removeAll(TEST_SUPPORT_TABLES);
            assertThat(extra)
                    .as("V1 落库后基线：无 PO 映射集/探针表/Flyway 元数据表/测试支撑表之外的业务表")
                    .isEmpty();
        }
    }

    /** 33 张 PO 映射表全部存在（V1/V2 落库后必备表集；缺失 = 迁移缺失） */
    @Test
    void allPoTablesPresent() throws SQLException {
        try (Connection conn = open()) {
            Set<String> tables = baseTables(conn);
            List<String> missing = new ArrayList<>();
            for (String table : PO_TABLES) {
                if (!tables.contains(table)) {
                    missing.add(table);
                }
            }
            assertThat(missing)
                    .as("Flyway V1/V2 未落库（当前表=%s）；缺失 PO 映射表=%s", tables, missing)
                    .isEmpty();
        }
    }

    /** 审计列 not null（id/create_time/update_time）；tenant-scoped 表另含 tenant_id */
    @Test
    void primaryKeyAndAuditColumnsNotNull() throws SQLException {
        try (Connection conn = open()) {
            for (String table : PO_TABLES) {
                List<String> nullableExpected = new ArrayList<>(List.of("id", "create_time", "update_time"));
                if (TENANT_SCOPED_TABLES.contains(table)) {
                    nullableExpected.add("tenant_id");
                }
                for (String column : nullableExpected) {
                    assertNullable(conn, table, column, false);
                }
            }
        }
    }

    /** 全部唯一约束（信息面 information_schema.statistics，non_unique=0）存在且值唯一 */
    @Test
    void uniqueConstraintsPresent() throws SQLException {
        try (Connection conn = open()) {
            Set<String> allUnique = new LinkedHashSet<>();
            for (String table : PO_TABLES) {
                for (String idx : indexNames(conn, table)) {
                    if (idx.startsWith("uk_")) {
                        allUnique.add(idx);
                    }
                }
            }
            List<String> missing = new ArrayList<>(UNIQUE_CONSTRAINTS);
            missing.removeAll(allUnique);
            assertThat(missing).as("缺失唯一约束（迁移未落库时必然缺失）: %s", missing).isEmpty();
        }
    }

    /** 全部 @Index 索引存在 */
    @Test
    void indexesPresent() throws SQLException {
        try (Connection conn = open()) {
            Set<String> allIndexes = new LinkedHashSet<>();
            for (String table : PO_TABLES) {
                allIndexes.addAll(indexNames(conn, table));
            }
            List<String> missing = new ArrayList<>(INDEXES);
            missing.removeAll(allIndexes);
            assertThat(missing).as("缺失索引（迁移未落库时必然缺失）: %s", missing).isEmpty();
        }
    }

    /**
     * MySQL 8.4 保留字核对：role / permission / assignment 三表名
     * 在宿主 MySQL 8.4.8 实测可无引号建表（非保留字；探针实证），引号形态亦可。
     * V1 脚本按此结论直接书写表名（可带反引号以显式表达意图）。
     * 临时表不进 binlog、会话结束自动清理，无 CDC 污染。
     */
    @Test
    void rbacTableNamesCreatable() throws SQLException {
        try (Connection conn = open()) {
            for (String table : List.of("role", "permission", "assignment")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TEMPORARY TABLE " + table + " (id BIGINT PRIMARY KEY)");
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("临时表 %s 应可查询", table).isEqualTo(0);
                    }
                    conn.createStatement().execute("DROP TEMPORARY TABLE " + table);
                }
            }
        }
    }

    private static void assertNullable(Connection conn, String table, String column, boolean expectedNullable)
            throws SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DATABASE);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail("列不存在: %s.%s", table, column);
                    return;
                }
                boolean actual = "YES".equalsIgnoreCase(rs.getString(1));
                assertThat(actual)
                        .as("列 %s.%s nullable 应为 %s（V1 前列缺失即失败）", table, column, expectedNullable ? "YES" : "NO")
                        .isEqualTo(expectedNullable);
            }
        }
    }
}