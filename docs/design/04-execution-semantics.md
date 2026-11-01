# 04 执行语义

## Execution 状态机

```
                    ┌────────── 链路故障（attempt ≤ 1+retry_count）→ 30s 后重试，同一 execution_id ──────────┐
                    │                                                                                       │
Quartz 触发 → PENDING（建行）→ 调用 Agent → RUNNING（收到受理回执 / SYNC 已收结果前）                          │
                    │                  │                                                                   │
                    │                  ├─ 2xx + AgentExecutionResult(SUCCEEDED) → SUCCEEDED（终态）        │
                    │                  ├─ 2xx + AgentExecutionResult(FAILED)    → FAILED（终态，不重试）      │
                    │                  ├─ SYNC 响应超时（timeout_seconds）       → TIMEOUT（终态，不重试）    │
                    │                  ├─ EVENT_CALLBACK ack 窗口超时/无 2xx/5xx  → 链路故障 → 重试 ───────────┘
                    │                  └─ ack 后 timeout_seconds 内未回调        → TIMEOUT（终态）
                    └── 重试穷尽 → FAILED（终态，last_error=穷尽原因）
```

- PENDING → RUNNING 的时机：AgentTriggerClient 发出请求前。
- 同一 execution_id 只生成一行；attempt 记录第几次触发尝试。
- 终态不可回退；TIMEOUT 后收到的回调按 03 的 409 处理。

## MISFIRE（跳过不补跑）

TaskJob 执行时：`now - scheduledFireTime > misfire-threshold（默认 60s）` → 不建 Execution、不调用 Agent，直接记 `MISFIRE` 事件（payload 带 scheduled_fire_time）。Quartz cron 触发器配 `MISFIRE_INSTRUCTION_DO_NOTHING` 双保险。

## 并发跳过（SKIPPED_CONCURRENT）

DispatchGuard 在建 Execution 前查 `executions WHERE task_id=? AND status IN ('PENDING','RUNNING')`；运行中数量 ≥ max_concurrent_executions → 不建行，记 `SKIPPED_CONCURRENT` 事件（payload 带 running_execution_id）。

## 链路重试

- 触发：链路故障 → 若 attempt < 1+retry_count：安排一次性重试（Quartz one-shot，30s 后，同一 execution_id，attempt+1，任务定义取当前生效版本）；否则置 FAILED。
- 推送：见 03 订阅；投递失败按 `push.retry-delays`（30s/5m/30m）重试 3 次，状态在 event_deliveries 推进（PENDING→…→EXHAUSTED）；DeliverySweeper 周期扫 `status='PENDING' AND next_retry_at<=now` 重新入队（覆盖重启恢复）。
- 业务 FAILED 与 TIMEOUT **永不重试**。

## 连续失败升级（ESCALATED）

- 终态为 FAILED 或 TIMEOUT → tasks.consecutive_failures +1；SUCCEEDED → 清零。
- 计数 **≥** 阈值（默认 3）的每次失败都记 `ESCALATED` 事件（task_id 有值，execution_id 为当次）并进入正常订阅推送；重复告警由订阅 dedup_window 压制。
- ESCALATED 不改变任务 status，不自动暂停。

## 幂等

- 调度侧：同一 execution_id 只发一次触发尝试（重试是新的 attempt，同一 id）。
- Agent 侧（契约要求，平台不强校验）：重复收到同 execution_id 触发应幂等。
- 回调侧：首次有效回调生效；重复回调幂等返回（见 03）。
