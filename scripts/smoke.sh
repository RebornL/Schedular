#!/usr/bin/env bash
# CI 冒烟：编译 → local profile 启动 → 无 Key 请求 /api/tasks 得 401 → 带 dev key 得到非 401 → 关停。
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v mvn >/dev/null 2>&1; then
  run_mvn() { mvn "$@"; }
elif [ -f "/d/tools/apache-maven-3.9.9/bin/mvn.cmd" ]; then
  run_mvn() { MSYS_NO_PATHCONV=1 cmd /c "D:\\tools\\apache-maven-3.9.9\\bin\\mvn.cmd $*"; }
elif [ -x ./mvnw ]; then
  run_mvn() { ./mvnw "$@"; }
fi
PORT=18080
JAR=$(ls target/schedular-*.jar 2>/dev/null | head -n 1 || true)

echo "==> 编译打包"
if [ -z "${JAR:-}" ]; then
  run_mvn -q -DskipTests package
fi
JAR=$(ls target/schedular-*.jar | head -n 1)

echo "==> 启动 local profile (端口 $PORT)"
rm -f schedular-smoke.db
java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; }
trap cleanup EXIT

for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "FAIL: 服务启动失败"; exit 1
  fi
done
echo "健康检查通过: $(curl -s "http://127.0.0.1:$PORT/health")"

# 兼容 Windows 原生 curl（不支持 -o /dev/null）：响应体 + 末行状态码
status_of() {
  curl -s -H "${2:+X-API-Key: $2}" -w '\n%{http_code}' "$1" | tail -n 1
}

CODE_NO_KEY=$(status_of "http://127.0.0.1:$PORT/api/tasks")
if [ "$CODE_NO_KEY" != "401" ]; then
  echo "FAIL: 无 Key 期望 401，实际 $CODE_NO_KEY"; exit 1
fi
echo "无 Key 访问 /api/tasks → 401 ✓"

CODE_DEV_KEY=$(status_of "http://127.0.0.1:$PORT/api/tasks" "schedular-dev-key")
if [ "$CODE_DEV_KEY" = "401" ]; then
  echo "FAIL: dev key 被拒绝（401）"; exit 1
fi
echo "带 schedular-dev-key 访问 /api/tasks → $CODE_DEV_KEY（非 401）✓"

echo "smoke OK"
