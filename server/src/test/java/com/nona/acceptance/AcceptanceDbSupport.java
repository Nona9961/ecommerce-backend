package com.nona.acceptance;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * E2E 验收真链断言共享数据面工具（纯 JDBC，无 Spring 上下文）。
 * <p>
 * 装配面：双连宿主隧道——MySQL {@code ecommerce_test}（localtunnel
 * 13306→3306，业务主库）与 PostgreSQL {@code ecommerce_test}（localtunnel
 * 15432→5432，CDC 镜像读库）。凭证从环境变量读取（运行命令模板从
 * /opt/data-stack/.env 映射导出：{@code ECOM_DB_PASSWORD} /
 * {@code ECOM_PG_PASSWORD}），本文件及任何测试文件不得出现密码明文。
 * <p>
 * 职责边界：直插/直查/poll 等待/幂等清理的工具函数——CDC 真链
 * AcTest 共用；业务断言仍写在各自测试类（断言
 * 面收口于 javadoc 启用契约）。
 * <p>
 * 测试分类合规：本类无 {@code @Test} 方法，为测试支撑类，不参与
 * 分类命名判定（TestFoundationContractAcTest 同口径）。
 *
 * @author nona9961
 */
public final class AcceptanceDbSupport {

    /** MySQL 隧道端口（宿主 3306）。 */
    public static final int MYSQL_PORT = 13306;

    /** PG 隧道端口（宿主 5432）。 */
    public static final int PG_PORT = 15432;

    /** 业务主库（验收面测试库，独立可 reset）。 */
    public static final String MYSQL_DB = "ecommerce_test";

    /** CDC 镜像读库（test 链镜像）。 */
    public static final String PG_DB = "ecommerce_test";

    private static final String MYSQL_HOST = "127.0.0.1";
    private static final String PG_HOST = "127.0.0.1";
    private static final String USER = "ecom_app";
    private static final String PASSWORD_ENV = "ECOM_DB_PASSWORD";
    private static final String PG_PASSWORD_ENV = "ECOM_PG_PASSWORD";

    private AcceptanceDbSupport() {
    }

    /** MySQL 测试库连接（主库直查/直插面）。 */
    public static Connection mysql() throws SQLException {
        final String password = System.getenv(PASSWORD_ENV);
        Objects.requireNonNull(password, "环境变量 " + PASSWORD_ENV
                + " 未设置（运行命令模板须从 MYSQL_ECOM_PW 映射导出）");
        return DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/" + MYSQL_DB
                        + "?connectTimeout=5000&socketTimeout=8000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, password);
    }

    /** PG 测试库连接（CDC 镜像 poll 面）。 */
    public static Connection pg() throws SQLException {
        final String password = System.getenv(PG_PASSWORD_ENV);
        Objects.requireNonNull(password, "环境变量 " + PG_PASSWORD_ENV
                + " 未设置（运行命令模板须从 ECOM_PG_PASSWORD 映射导出）");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + PG_DB,
                USER, password);
    }

    /**
     * poll 等待：每 500ms 重试直至检查器为真或超时——CDC 最终一致
     * （秒级延迟）的断言等待面（61 纪律：镜像不收敛先区分数据面，
     * 不以固定睡眠猜测收敛时刻）。
     *
     * @param check       检查器（直查镜像/业务表的布尔谓词）
     * @param description 断言描述（超时信息面）
     * @param timeout     等待窗口（相对时间，禁用绝对日期魔法值）
     */
    public static void pollUntil(Supplier<Boolean> check, String description, Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(check.get())) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("poll 等待被打断: " + description, e);
            }
        }
        throw new AssertionError("poll 超时（" + timeout + "）: " + description);
    }

    /** 行数直查（参数化）。 */
    public static long count(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 单值直查（字符串列；无行返回 null）。 */
    public static String stringValue(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 参数化 DML（INSERT/UPDATE/DELETE）；返回受影响行数。 */
    public static int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** 主键定位行存在性直查。 */
    public static boolean exists(Connection conn, String table, long id) throws SQLException {
        return count(conn, "SELECT COUNT(*) FROM " + table + " WHERE id = ?", id) > 0;
    }

    /** 唯一 UUID（探针/账号/单号造数用，避免跨运行碰撞）。 */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前 UTC 时刻（MySQL DATETIME(6) 直插/相对时间窗口用）。 */
    public static Timestamp utcNow() {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** 相对窗口时刻（now ± offsetMinutes 分钟；UTC 字面——与部署位对齐）。 */
    public static Timestamp utcOffset(int offsetMinutes) {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(offsetMinutes));
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /**
     * 挂载日志收集 appender 到指定 logger（事件异步日志留痕断言面）。
     * <p>
     * 不依赖 Spring Boot OutputCaptureExtension（System.out 替换）：多测试类
     * 同 JVM 下输出捕获只对首个加载 Spring context 的类有效（本项目日志
     * 实现为 log4j2，控制台 appender 的流绑定同理）——后续类的 CapturedOutput
     * 收不到异步日志（-Pfull 全量验收必红，实测）。本 helper 用 log4j2
     * 原生 appender 直收目标 logger 事件，与输出流捕获机制无关，跨类稳定。
     * <p>
     * 事件列表以 CopyOnWriteArrayList 承载（异步日志线程 append 与断言轮询
     * 线程读并发安全）；每方法挂载/卸载（方法级隔离，防跨方法累积）。
     *
     * @param loggerClass 事件日志监听器类（logger 名 = 类名）
     * @return 已启动的收集 appender（{@link #events()} 承载格式化消息）
     */
    public static ListLogAppender installLogAppender(Class<?> loggerClass) {
        final Logger logger = (Logger) LogManager.getLogger(loggerClass);
        final ListLogAppender appender = new ListLogAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 卸载日志收集 appender（与 {@link #installLogAppender} 配对；每方法
     * 清理，防跨方法累积）。
     *
     * @param loggerClass 事件日志监听器类
     * @param appender    已挂载的 appender 实例
     */
    public static void detachLogAppender(Class<?> loggerClass, ListLogAppender appender) {
        final Logger logger = (Logger) LogManager.getLogger(loggerClass);
        logger.removeAppender(appender);
    }

    /**
     * 测试事件日志收集 appender（log4j2 原生，直收格式化消息）。
     */
    public static final class ListLogAppender extends AbstractAppender {

        private final List<String> events = new CopyOnWriteArrayList<>();

        private ListLogAppender() {
            super("WU49TestListAppender", null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getMessage() != null) {
                events.add(event.getMessage().getFormattedMessage());
            }
        }

        /**
         * @return 已收集的格式化日志消息（append 序，线程安全）
         */
        public List<String> events() {
            return events;
        }
    }
}
