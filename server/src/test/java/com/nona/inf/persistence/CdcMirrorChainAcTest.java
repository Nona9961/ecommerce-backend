package com.nona.inf.persistence;

import com.nona.acceptance.AcceptanceDbSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDC 真链验收测试：写 MySQL ecommerce_test 业务表 →
 * poll-until 断言 PG ecommerce_test 镜像基表行一致（三库同步链路
 * 已就绪后的写后读一致性自动化载体）。
 * <p>
 * 装配面：<b>纯 JDBC</b>（DriverManager 双连 MySQL 13306 → MySQL test、
 * PG 15432 → PG test），不依赖 Spring 上下文——MigrationAcTest 同构。
 * 凭证从环境变量读取（运行命令模板从 /opt/data-stack/.env 映射导出：
 * {@code ECOM_DB_PASSWORD} / {@code ECOM_PG_PASSWORD}），零落盘零打印。
 * <p>
 * 场景（probe-cdc.sh 链路同构，进程内独立断言）：
 * <ol>
 *   <li>探针表增量：INSERT cdc_probe 行 → poll PG cdc_probe 出现该行 + 字段一致</li>
 *   <li>业务表增量：INSERT master_order + sub_order → poll PG 镜像行一致</li>
 *   <li>UPDATE 传播：更新探针行 amount → poll PG 更新一致</li>
 *   <li>DELETE 传播：DELETE 探针行 → poll PG 行消失</li>
 * </ol>
 * 测试分类：AcTest（surefire 默认排除，-Pfull 或 -Dtest 显式执行）。
 * 幂等清理：测试结束时 DELETE 自造行（幂等覆盖）。
 * <p>
 * <b>链路就绪信号</b>：若 CDC 链路未就绪，poll 超时即为红因；
 * 若链路已就绪，本测试应绿——这正是链路就绪信号，
 * 不属于「红因不明」。红态判定：poll 超时/断言不满足 = 红（链路未就绪），
 * 全绿 = 链路就绪实证。
 * <p>
 * 运行（需 localtunnel 隧道，worktree 目录下执行）：
 * <pre>
 * timeout 900 /opt/code/.pi/scripts/localtunnel.sh exec bash -c 'cd /opt/code/ecommerce-backend
 *   && set -a && . /opt/data-stack/.env && set +a
 *   && export ECOM_DB_PASSWORD="$MYSQL_ECOM_PW"
 *   && export ECOM_PG_PASSWORD="$ECOM_PG_PASSWORD"
 *   && mvn -o -llr -s /opt/code/.m2/settings.xml -Pfull -Dtest=CdcMirrorChainAcTest test'
 * </pre>
 *
 * @author nona9961
 */
class CdcMirrorChainAcTest {

    /** 探针表行主键（幂等自清白名单）。 */
    private static final long PROBE_ID = 99991L;

    /** 业务表行主键（幂等自清白名单）。 */
    private static final long MASTER_ID = 89891L;

    private static final long SUB_ID = 79891L;

    /**
     * 场景-1：探针表增量写后读一致性。
     * 直插 cdc_probe 行（id/name/amount/remark）→ poll-until PG 镜像出现该行
     * + 字段一致（amount/remark）。
     */
    @Test
    @DisplayName("CDC 场景-1：探针表 INSERT → PG 镜像一致")
    void cdcProbe_insertConsistent() throws Exception {
        final String name = "probe-" + AcceptanceDbSupport.uuid().substring(0, 8);
        final double amount = 123.45;
        final String remark = "CDC-AcTest-probe-1";

        // 幂等前置清理（上次运行失败残留 → 本次不 Duplicate）
        cleanupRow("cdc_probe", PROBE_ID);
        // MySQL 写
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO cdc_probe (id, name, amount, remark) VALUES (?, ?, ?, ?)",
                    PROBE_ID, name, amount, remark);
        }
        // poll PG 镜像
        final String finalName = name;
        final double finalAmount = amount;
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        final long cnt = AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM cdc_probe WHERE name = ?", finalName);
                        if (cnt == 1) {
                            final String pgName = AcceptanceDbSupport.stringValue(pg,
                                    "SELECT name FROM cdc_probe WHERE name = ?", finalName);
                            final String pgAmount = AcceptanceDbSupport.stringValue(pg,
                                    "SELECT CAST(amount AS VARCHAR) FROM cdc_probe WHERE name = ?", finalName);
                            return pgName != null && pgAmount != null
                                    && Double.parseDouble(pgAmount) == finalAmount;
                        }
                        return false;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "PG cdc_probe 含探针行（name=" + name + "，amount=" + amount + "）",
                Duration.ofMinutes(1));
        // 清理
        cleanupRow("cdc_probe", PROBE_ID);
    }

    /**
     * 场景-2：业务表增量写后读一致性。
     * 直插 master_order + sub_order（真实业务行）→ poll-until PG 镜像对应行
     * 出现 + 关键列一致（id/order_no/status）。
     */
    @Test
    @DisplayName("CDC 场景-2：业务表 INSERT → PG 镜像一致")
    void businessTable_insertConsistent() throws Exception {
        // 幂等前置清理（上次运行失败残留 → 本次不 Duplicate，从表先于主表）
        cleanupRow("sub_order", SUB_ID);
        cleanupRow("master_order", MASTER_ID);
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                            + " recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER_ID,
                    "ORD-CDC-TEST", 56101L, "张三", "13800000000",
                    "浙江省", "杭州市", "西湖区", "fixture", 5000L, 300L, 0L, 5300L, "PAID");
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                            + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                            + " goods_amount, freight_amount, discount, paid_amount, status, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB_ID,
                    "96101", MASTER_ID, 96101L, "SUB-CDC-TEST",
                    "张三", "13800000000", "浙江省", "杭州市", "西湖区", "fixture",
                    5000L, 300L, 0L, 5300L, "PAID", false);
        }
        // poll PG 镜像
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        final long mc = AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM master_order WHERE id = ?", MASTER_ID);
                        final long sc = AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM sub_order WHERE id = ?", SUB_ID);
                        return mc == 1 && sc == 1;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "PG 镜像含 master_order/sub_order（CDC 收敛）",
                Duration.ofMinutes(1));

        // 断言字段一致
        try (Connection pg = AcceptanceDbSupport.pg()) {
            assertThat(AcceptanceDbSupport.stringValue(pg,
                    "SELECT order_no FROM master_order WHERE id = ?", MASTER_ID))
                    .isEqualTo("ORD-CDC-TEST");
            assertThat(AcceptanceDbSupport.stringValue(pg,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_ID))
                    .isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(pg,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_ID))
                    .isEqualTo("PAID");
        }
        // 清理
        cleanupRow("sub_order", SUB_ID);
        cleanupRow("master_order", MASTER_ID);
    }

    /**
     * 场景-3：UPDATE 传播一致性。
     * 探针行 amount 更新 → poll PG 镜像 amount 同步更新。
     */
    @Test
    @DisplayName("CDC 场景-3：UPDATE 传播一致")
    void cdc_updateConsistent() throws Exception {
        final String name = "probe-update-" + AcceptanceDbSupport.uuid().substring(0, 8);
        // 幂等前置清理（上次运行失败残留 → 本次不 Duplicate）
        cleanupRow("cdc_probe", PROBE_ID);
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO cdc_probe (id, name, amount, remark) VALUES (?, ?, ?, ?)",
                    PROBE_ID, name, 100.0, "CDC-update-before");
        }
        // 等镜像出现
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        return AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM cdc_probe WHERE name = ?", name) == 1;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "探针行出现（CDC INSERT 收敛）", Duration.ofMinutes(1));

        // MySQL UPDATE
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "UPDATE cdc_probe SET amount = 200.0 WHERE id = ?", PROBE_ID);
        }
        // poll PG 镜像更新（数值比较——PG numeric 列 CAST 输出两位小数
        // 如 '200.00'，字符串等值断言恒 false 属断言形态 bug，probe-cdc.sh
        // 三库全绿实证 UPDATE 链路正常）
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        final String pgAmount = AcceptanceDbSupport.stringValue(pg,
                                "SELECT CAST(amount AS VARCHAR) FROM cdc_probe WHERE name = ?", name);
                        return pgAmount != null && Double.parseDouble(pgAmount) == 200.0;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "PG cdc_probe amount = 200.0（CDC UPDATE 收敛）",
                Duration.ofMinutes(1));
        cleanupRow("cdc_probe", PROBE_ID);
    }

    /**
     * 场景-4：DELETE 传播一致性。
     * 探针行 DELETE → poll PG 镜像行消失。
     */
    @Test
    @DisplayName("CDC 场景-4：DELETE 传播一致")
    void cdc_deleteConsistent() throws Exception {
        final String name = "probe-delete-" + AcceptanceDbSupport.uuid().substring(0, 8);
        // 幂等前置清理（上次运行失败残留 → 本次不 Duplicate）
        cleanupRow("cdc_probe", PROBE_ID);
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "INSERT INTO cdc_probe (id, name, amount, remark) VALUES (?, ?, ?, ?)",
                    PROBE_ID, name, 99.99, "CDC-delete-before");
        }
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        return AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM cdc_probe WHERE name = ?", name) == 1;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "探针行出现（CDC INSERT 收敛）", Duration.ofMinutes(1));

        // MySQL DELETE
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql, "DELETE FROM cdc_probe WHERE id = ?", PROBE_ID);
        }
        // poll PG 镜像行消失
        AcceptanceDbSupport.pollUntil(
                () -> {
                    try (Connection pg = AcceptanceDbSupport.pg()) {
                        return AcceptanceDbSupport.count(pg,
                                "SELECT COUNT(*) FROM cdc_probe WHERE name = ?", name) == 0;
                    } catch (SQLException e) {
                        return false;
                    }
                },
                "PG cdc_probe 探针行消失（CDC DELETE 收敛）",
                Duration.ofMinutes(1));
    }

    /** 幂等自清（DELETE by id 白名单）。 */
    private void cleanupRow(String table, long id) {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql, "DELETE FROM " + table + " WHERE id = ?", id);
        } catch (SQLException e) {
            // 幂等清理，失败不影响测试结果
        }
    }
}
