package com.nona.inf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试底座契约（验收面）：锁定「测试分类两分 + 执行排除收敛 + 测试数据源切
 * 真库」的改造完成态。
 * <p>
 * 装配面：<b>纯文件系统断言</b>（扫描 {@code server/src/test/java} 文件名 +
 * 读取后端根 {@code pom.xml} 与 {@code application-test.yml}），无 Spring
 * 上下文、无数据库、无 mock——验证对象是仓库静态结构而非运行行为。
 * <p>
 * 本项目测试分类为两分（命名即分类）：
 * <ul>
 * <li>单元：{@code XxxUnitTest}（依赖全 mock，无 Spring 容器）——开发期默认面；</li>
 * <li>验收：{@code XxxAcTest}（真库全链路 / 底座契约验收面）——{@code -Pfull} 全量；</li>
 * </ul>
 * 旧测试形态后缀（{@code XxxTest} / {@code XxxTests} / {@code XxxIntegrationTest} /
 * {@code XxxSmokeTest}）不允许残留：冒烟概念已废弃，原冒烟类按命名映射改名为
 * {@code XxxAcTest}。
 * <p>
 * 执行（红阶段红态验证 / 绿阶段验收同形）：
 * <pre>
 * cd /opt/code/ecommerce-backend &amp;&amp; mvn -o -llr -s /opt/code/.m2/settings.xml \
 *   -Pfull -Dtest=TestFoundationContractAcTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 *
 * @author nona9961
 */
class TestFoundationContractAcTest {

    /** 冒烟类改名映射（旧 SmokeTest 基名 → 新 AcTest 基名），随分类改革固化。 */
    private static final Map<String, String> RENAME_MAP = new LinkedHashMap<>();
    static {
        RENAME_MAP.put("CancelOrderSmokeTest", "CancelOrderAcTest");
        RENAME_MAP.put("ConfirmReceiptSmokeTest", "ConfirmReceiptAcTest");
        RENAME_MAP.put("RefundFlowSmokeTest", "RefundFlowAcTest");
        RENAME_MAP.put("TimeoutHandlersSmokeTest", "TimeoutHandlersAcTest");
        RENAME_MAP.put("ShipOrderSmokeTest", "ShipOrderAcTest");
        RENAME_MAP.put("PaymentCallbackSmokeTest", "PaymentCallbackAcTest");
        RENAME_MAP.put("PlatformLogisticsViewSmokeTest", "PlatformLogisticsViewAcTest");
        RENAME_MAP.put("LogisticsSimulatorSmokeTest", "LogisticsSimulatorAcTest");
        RENAME_MAP.put("MigrationSmokeTest", "MigrationAcTest");
        RENAME_MAP.put("TradingPoFoundationSmokeTest", "TradingPoFoundationAcTest");
        RENAME_MAP.put("RepoContractSmokeTest", "RepoContractAcTest");
    }

    /** 测试源根（相对仓库根）。 */
    private static final Path TEST_SRC_ROOT = Path.of("server", "src", "test", "java");

    /** 测试资源配置（相对仓库根）。 */
    private static final Path TEST_YML = Path.of("server", "src", "main", "resources", "application-test.yml");

    /** 从 workdir 向上定位仓库根：首个同时含 server/ 目录与 pom.xml 的父目录。 */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && current.getParent() != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("server"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根（未找到含 pom.xml 且含 server/ 的父目录），workdir="
                + Path.of("").toAbsolutePath());
    }

    /** {@code server/src/test/java} 下全部测试文件基名（去 .java 后缀）。 */
    private static List<String> testClassBaseNames(Path root) throws IOException {
        Path srcRoot = root.resolve(TEST_SRC_ROOT);
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 含 {@code @Test}/{@code @ParameterizedTest} 注解的测试文件基名（支撑类/
     * fixture/探针无测试方法，不参与分类合规判定——与机械检查口径一致）。
     * <p>
     * 识别规则：按行扫描，跳过 javadoc/注释行（{@code *}/{\@code //} 开头）后
     * 仍含注解字面才计入——支撑类 javadoc 中「本类无 {@code @Test} 方法」等
     * 字样不误判（WU-49 AcceptanceDbSupport 触发实证）。
     */
    private static List<String> annotatedTestClassBaseNames(Path root) throws IOException {
        Path srcRoot = root.resolve(TEST_SRC_ROOT);
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).lines()
                                    .anyMatch(line -> !line.stripLeading().startsWith("*")
                                            && !line.stripLeading().startsWith("//")
                                            && (line.contains("@Test") || line.contains("@ParameterizedTest")));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 改名后目标文件全部就位：每个冒烟旧名对应的新 AcTest 基名都存在于测试源，
     * 且旧 SmokeTest 基名全部消失（双向锁定，杜绝「改名一半」）。
     */
    @Test
    void renameMappingApplied() throws IOException {
        Path root = repoRoot();
        List<String> names = testClassBaseNames(root);
        List<String> missingTargets = RENAME_MAP.values().stream()
                .filter(target -> !names.contains(target))
                .collect(Collectors.toList());
        List<String> remainingLegacy = names.stream()
                .filter(RENAME_MAP::containsKey)
                .collect(Collectors.toList());
        assertThat(missingTargets)
                .as("改名映射目标缺失（仍为旧 SmokeTest 命名，改造未完成）: %s", missingTargets)
                .isEmpty();
        assertThat(remainingLegacy)
                .as("旧 SmokeTest 命名仍残留（冒烟分类已废弃，须改名为 AcTest）: %s", remainingLegacy)
                .isEmpty();
    }

    /**
     * 测试分类命名两分合规：含测试方法的文件基名必须以 {@code XxxUnitTest} 或
     * {@code XxxAcTest} 结尾；任何旧形态后缀（{@code XxxTest}/{@code XxxTests}/
     * {@code XxxIntegrationTest}/{@code XxxSmokeTest}）均为违规。
     * <p>
     * 分类锚点：文件基名 = 分类来源（红阶段机械检查与验收检查同口径）；支撑类
     * （fixture/探针/测试服务，无 {@code @Test} 方法）不参与分类判定。
     */
    @Test
    void classificationNamingCompliant() throws IOException {
        Path root = repoRoot();
        List<String> names = annotatedTestClassBaseNames(root);
        List<String> violations = names.stream()
                .filter(name -> !name.endsWith("UnitTest") && !name.endsWith("AcTest"))
                .collect(Collectors.toList());
        assertThat(violations)
                .as("测试文件命名不合两分（须以 UnitTest/AcTest 结尾）: %s", violations)
                .isEmpty();
    }

    /**
     * surefire 排除收敛为恰好一条：默认面排除 {@code **\/*AcTest}，不得残留
     * {@code **\/*SmokeTest} 排除（冒烟分类废弃后收敛）。
     * <p>
     * 读取后端根 {@code pom.xml} 的 surefire 配置段，断言：
     * <ul>
     * <li>存在且仅存在一条 {@code <exclude>**\/*AcTest</exclude>}；</li>
     * <li>不存在任何 {@code *SmokeTest} 排除条目。</li>
     * </ul>
     */
    @Test
    void surefireExclusionConverged() throws IOException {
        Path root = repoRoot();
        String pom = Files.readString(root.resolve("pom.xml"));
        long acExcludes = occurrences(pom, "<exclude>**/*AcTest</exclude>");
        long smokeExcludes = occurrences(pom, "SmokeTest");
        assertThat(acExcludes)
                .as("surefire 排除应收敛为恰好一条 **/*AcTest（默认面只收单元）")
                .isEqualTo(1);
        assertThat(smokeExcludes)
                .as("surefire 配置不得残留 SmokeTest 排除条目（冒烟分类已废弃）")
                .isZero();
    }

    /**
     * 测试主库指向独立测试库：{@code application-test.yml} 主数据源 URL 必须指向
     * {@code ecommerce_test}（验收面与开发/演示库分库隔离，测试库独立可 reset）。
     * <p>
     * 隔离纪律：验收面禁止写入开发/演示库（测试数据零残留、环境一致）。
     * <p>
     * 解析：YAML 嵌套段（{@code spring.datasource.url}），取首个 {@code jdbc:mysql://}
     * 行即主库 URL。
     */
    @Test
    void testYmlPrimaryPointsToTestDb() throws IOException {
        Path root = repoRoot();
        String yml = Files.readString(root.resolve(TEST_YML));
        String primaryUrl = firstLineContaining(yml, "jdbc:mysql://");
        assertThat(primaryUrl)
                .as("测试主库 URL 必须指向 ecommerce_test 测试库")
                .contains("ecommerce_test");
        assertThat(primaryUrl)
                .as("测试主库不得指向开发/演示库 ecommerce")
                .doesNotContain("/ecommerce?");
    }

    /**
     * 测试 replica 指向真 PG：{@code application-test.yml} 的 replica 段数据源 URL
     * 必须为真实 PostgreSQL（消除 H2 {@code MODE=PostgreSQL} 模拟与真库的方言不一致）。
     * <p>
     * 解析：replica 段为独立顶层命名空间（{@code replica.datasource.url}），取首个
     * {@code jdbc:postgresql://} 或 {@code jdbc:h2:} 行即 replica URL。
     */
    @Test
    void testYmlReplicaPointsToRealPg() throws IOException {
        Path root = repoRoot();
        String yml = Files.readString(root.resolve(TEST_YML));
        String replicaUrl = firstLineContainingEither(yml, "jdbc:postgresql://", "jdbc:h2:");
        assertThat(replicaUrl)
                .as("测试 replica 数据源应指向真实 PostgreSQL（jdbc:postgresql://...）")
                .contains("jdbc:postgresql://");
        assertThat(replicaUrl)
                .as("测试 replica 不得再使用 H2 模拟（MODE=PostgreSQL 与真库方言不一致）")
                .doesNotContain("jdbc:h2:");
    }

    private static String firstLineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new IllegalStateException("未找到包含 " + needle + " 的行");
    }

    private static String firstLineContainingEither(String text, String a, String b) {
        for (String line : text.split("\n")) {
            if (line.contains(a) || line.contains(b)) {
                return line;
            }
        }
        throw new IllegalStateException("未找到包含 " + a + " 或 " + b + " 的行");
    }

    private static long occurrences(String text, String needle) {
        long count = 0;
        int from = 0;
        while (true) {
            int i = text.indexOf(needle, from);
            if (i < 0) {
                return count;
            }
            count++;
            from = i + needle.length();
        }
    }
}
