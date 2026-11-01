#!/usr/bin/env bash
# 工单 #12：全量测试 + 报告生成 + 数据一致性检查。
# 用法：bash scripts/verify.sh   （在仓库/worktree 根或任意目录执行）
set -euo pipefail
cd "$(dirname "$0")/.."

DATE="$(date +%F)"
REPORT="docs/verification/${DATE}-e2e-report.md"
LOG=".verify-mvn.log"

COMMIT="$(git rev-parse HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
JAVA_VER="$(java -version 2>&1 | head -1)"
OS_INFO="$(uname -s -r) / $(uname -m)"

echo "[verify] 1/3 运行全量 mvn test ..."
MSYS_NO_PATHCONV=1 cmd /c 'D:\tools\apache-maven-3.9.9\bin\mvn.cmd -B test' 2>&1 | tee "$LOG"
MVN_EXIT="${PIPESTATUS[0]}"
if [ "$MVN_EXIT" -ne 0 ]; then
    echo "[verify] mvn test 失败（exit=$MVN_EXIT），不生成报告"
    exit 1
fi

echo "[verify] 2/3 汇总 surefire 结果 ..."
SUMMARY_LINE="$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$LOG" | tail -1 | tr -d '\r')"
TESTS="$(echo "$SUMMARY_LINE" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')"
FAILURES="$(echo "$SUMMARY_LINE" | sed -E 's/.*Failures: ([0-9]+).*/\1/')"
ERRORS="$(echo "$SUMMARY_LINE" | sed -E 's/.*Errors: ([0-9]+).*/\1/')"
SKIPPED="$(echo "$SUMMARY_LINE" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')"
if [ -z "$SUMMARY_LINE" ]; then
    echo "[verify] 未找到 surefire 汇总行，验收失败"
    exit 1
fi

# 数据一致性检查：E2E 用例输出的 ASCII 全绿标记（避免 Windows JVM stdout GBK 编码导致 grep 失配）
CONSISTENCY="$(grep -ah 'DATA_CONSISTENCY_CHECK_OK' target/surefire-reports/*.txt "$LOG" 2>/dev/null | tail -1 | tr -d '\r')"

if [ "$FAILURES" -ne 0 ] || [ "$ERRORS" -ne 0 ] || [ -z "$CONSISTENCY" ]; then
    echo "[verify] 验收失败: failures=$FAILURES errors=$ERRORS consistency='${CONSISTENCY:-未找到}'"
    exit 1
fi

echo "[verify] 3/3 生成报告 $REPORT ..."
mkdir -p docs/verification
cat > "$REPORT" <<EOF
# E2E 验收报告（工单 #12）

- 日期：${DATE}
- 代码版本：${COMMIT}（分支 ${BRANCH}）
- 环境：${JAVA_VER}；${OS_INFO}
- 可复现：本报告由 \`bash scripts/verify.sh\` 生成，执行以下命令可复现同样结果：
  \`\`\`
  MSYS_NO_PATHCONV=1 cmd /c 'D:\\tools\\apache-maven-3.9.9\\bin\\mvn.cmd -B test'
  \`\`\`

## 测试结果

- 全量结果：Tests run: ${TESTS}, Failures: ${FAILURES}, Errors: ${ERRORS}, Skipped: ${SKIPPED}
- E2E 验收用例：\`com.schedular.e2e.EndToEndAcceptanceTest\`（主链路 SYNC / 订阅 HMAC 推送 /
  EVENT_CALLBACK / 失败升级 ESCALATED / 版本化穿插 / 数据一致性检查）与
  \`com.schedular.e2e.EndToEndMisfireAcceptanceTest\`（MISFIRE 无执行）
- 数据一致性检查：数据一致性检查：全绿（8 项不变量，标记 ${CONSISTENCY}）——执行版本引用、状态枚举、
  终态留痕、attempt、事件-执行外键、事件-状态一致、投递无孤儿、连续失败计数重放。

## 未验证项（如实记录）

- MySQL 真库建表/迁移验证缺席：本机无 Docker，仅验证 Flyway SQLite 迁移。
- Quartz 生产 JDBC JobStore profile 未运行验证：测试均使用 local/test profile 的 RAMJobStore。
- 本报告由脚本生成，全量结果可由上述命令复现。
EOF

echo "[verify] 完成：Tests run: ${TESTS}, Failures: ${FAILURES}, Errors: ${ERRORS}, Skipped: ${SKIPPED}"
echo "数据一致性检查：全绿（8 项不变量）"
