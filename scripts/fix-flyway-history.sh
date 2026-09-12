#!/usr/bin/env bash
# =============================================================================
# Flyway 历史修复（repair 语义）：删除 ecommerce 测试库 flyway_schema_history
# 中已应用但 dev locations 解析不到的 R__tenant_test_tables 记录（测试面
# 迁移与 dev 共库导致的校验失败：dev 启动报 "Detected applied migration
# not resolved locally"）。
# 前置：application-dev.yml 已配 ignore-migration-patterns=repeatable:missing
#   （版本化 V 迁移仍严格校验）——本脚本为存量历史记录的一次性修复兜底。
# 用法（隧道会话内）：
#   export ECOM_DB_PASSWORD="$MYSQL_ECOM_PW"        # 凭证零落盘零打印
#   ./scripts/fix-flyway-history.sh
# 行为：备份目标行（输出）→ DELETE → 复查剩余 history 行。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR="$(find /opt/code/.m2/repository/com/mysql/mysql-connector-j \
    -name 'mysql-connector-j-*.jar' 2>/dev/null | sort -V | tail -1)"
if [[ -z "${CONNECTOR}" || ! -f "${CONNECTOR}" ]]; then
    echo "FATAL: mysql-connector-j jar 未在本地 m2 找到" >&2
    exit 2
fi

JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi

export JDBC_URL="${JDBC_URL:-jdbc:mysql://127.0.0.1:13306/ecommerce_test?useSSL=false&allowPublicKeyRetrieval=true}"
export JDBC_USER="${JDBC_USER:-ecom_app}"
export JDBC_PWD_VAR="${JDBC_PWD_VAR:-MYSQL_ECOM_PW}"
export MYSQL_ECOM_PW="${ECOM_DB_PASSWORD:?环境变量缺失：ECOM_DB_PASSWORD}"

exec "${JAVA_BIN}" --class-path "${CONNECTOR}" "${SCRIPT_DIR}/FixFlywayHistory.java"