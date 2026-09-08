import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据清理（WU-53 F6）：数据级 DELETE，绝不 DROP / DROP DATABASE / Flyway clean。
 * <p>
 * 由 {@code test-db-reset.sh} 以 JDK 单文件源码模式调用（JEP 330，零编译步骤）；
 * 凭证走环境变量（ECOM_DB_PASSWORD 必填，host/port/db/user 可选覆盖），零落盘零打印。
 * <p>
 * 语义：
 * <ul>
 * <li>{@code information_schema} 动态枚举 ecommerce 库 BASE TABLE，排除部署位探针表
 *     {@code cdc_probe} 与 Flyway 元数据表 {@code flyway_schema_history}——未来 V2+ 新表
 *     自动纳入，无表清单硬编码；</li>
 * <li>{@code SET FOREIGN_KEY_CHECKS=0} 双保险（PO 零关联注解、库中本无 FK，属过防御）；
 *     脚本结束恢复 FOREIGN_KEY_CHECKS=1；</li>
 * <li>清理后逐表重查 {@code COUNT(*)} 断言表空（非 0 即非零退出码）；DELETE 影响行数
 *     一并输出（造数 → 清理 → 空断言两轮验证用）。</li>
 * </ul>
 * 表名取自 information_schema（服务端元数据），非外部输入，无注入面。
 */
public final class TestDbReset {

    private TestDbReset() {
    }

    public static void main(String[] args) throws Exception {
        String password = System.getenv("ECOM_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("环境变量 ECOM_DB_PASSWORD 未设置（运行模板从 MYSQL_ECOM_PW 映射导出）");
        }
        String host = envOr("ECOM_DB_HOST", "127.0.0.1");
        String port = envOr("ECOM_DB_PORT", "13306");
        String db = envOr("ECOM_DB_NAME", "ecommerce");
        String user = envOr("ECOM_DB_USER", "ecom_app");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?connectTimeout=5000&socketTimeout=8000&useSSL=false&allowPublicKeyRetrieval=true";

        Connection conn = DriverManager.getConnection(url, user, password);
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                        + "AND table_name NOT IN ('cdc_probe', 'flyway_schema_history') "
                        + "ORDER BY table_name")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }

        boolean failed = false;
        try (Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                for (String table : tables) {
                    try {
                        int deleted = st.executeUpdate("DELETE FROM `" + table + "`");
                        long remaining = 0L;
                        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                            if (rs.next()) {
                                remaining = rs.getLong(1);
                            }
                        }
                        System.out.printf("OK %-40s deleted=%-6d remaining=%d%n", table, deleted, remaining);
                        if (remaining != 0L) {
                            failed = true;
                        }
                    } catch (SQLException e) {
                        failed = true;
                        System.err.printf("FAIL %s: %s%n", table, e.getMessage());
                    }
                }
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
        if (tables.isEmpty()) {
            System.out.println("OK (no tables to clean)");
        }
        conn.close();
        if (failed) {
            System.exit(1);
        }
        System.out.println("RESET DONE");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}