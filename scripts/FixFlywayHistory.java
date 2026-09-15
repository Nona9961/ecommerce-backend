import java.sql.*;

/**
 * Flyway repair 语义：备份并删除库中
 * flyway_schema_history 已应用但 dev locations 解析不到的 R__tenant_test_tables
 * 记录（test 面重放幂等——R 迁移 IF NOT EXISTS）。
 * 步骤：备份行(输出) → DELETE → 复查剩余行。
 */
public class FixFlywayHistory {
    public static void main(String[] a) throws Exception {
        String url = System.getenv("JDBC_URL");
        String user = System.getenv("JDBC_USER");
        String pw = System.getenv(System.getenv("JDBC_PWD_VAR"));
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            // 1. 备份目标行（全字段输出）
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success " +
                     "FROM flyway_schema_history WHERE description = 'tenant test tables'")) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    StringBuilder sb = new StringBuilder("BACKUP: ");
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                        sb.append(rs.getMetaData().getColumnLabel(i)).append("=").append(rs.getString(i)).append(" | ");
                    System.out.println(sb);
                }
                if (!found) { System.out.println("NO ROW: tenant test tables 不存在，无需修复"); return; }
            }
            // 2. DELETE（按 description，精确单行）
            int del;
            try (Statement st = c.createStatement()) {
                del = st.executeUpdate("DELETE FROM flyway_schema_history WHERE description = 'tenant test tables'");
            }
            System.out.println("DELETED rows=" + del);
            // 3. 复查剩余
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT installed_rank, version, description, type FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rs.next())
                    System.out.println("REMAIN: rank=" + rs.getInt(1) + " version=" + rs.getString(2)
                            + " desc=" + rs.getString(3) + " type=" + rs.getString(4));
            }
        }
    }
}