#!/usr/bin/env bash
# =============================================================================
# 测试数据清理（WU-53 F6）：数据级 DELETE，绝不允许 DROP / DROP DATABASE /
# Flyway clean（本库是 CDC/Debezium 源库，drop/clean 会炸整条 binlog 同步链路）。
# =============================================================================
# 用法：由运行命令模板执行（隧道会话内）：
#   export ECOM_DB_PASSWORD="$MYSQL_ECOM_PW"        # 凭证零落盘零打印
#   ./scripts/test-db-reset.sh
# 可选覆盖：ECOM_DB_HOST / ECOM_DB_PORT / ECOM_DB_NAME / ECOM_DB_USER
# 依赖：JDK 25（JEP 330 单文件源码模式，零编译）+ 本地 m2 的 mysql-connector-j
#       （无 mysql CLI 依赖）。
# 行为：information_schema 动态枚举 ecommerce 库 BASE TABLE（排除 cdc_probe /
#       flyway_schema_history）逐表 DELETE；SET FOREIGN_KEY_CHECKS=0 双保险；
#       清理后逐表 COUNT(*) 断言表空（非 0 → 非零退出码）。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR="$(find /opt/code/.m2/repository/com/mysql/mysql-connector-j \
    -name 'mysql-connector-j-*.jar' 2>/dev/null | sort -V | tail -1)"
if [[ -z "${CONNECTOR}" || ! -f "${CONNECTOR}" ]]; then
    echo "FATAL: mysql-connector-j jar 未在本地 m2 找到" >&2
    exit 2
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
else
    JAVA_BIN="java"
fi

exec "${JAVA_BIN}" --class-path "${CONNECTOR}" "${SCRIPT_DIR}/TestDbReset.java" "$@"