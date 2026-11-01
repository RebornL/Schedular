# 03 协议

字段命名 snake_case（对外 JSON）；错误体统一 `{ "code": "<MACHINE_CODE>", "message": "<中文可读>" }`，HTTP 状态码 4xx/5xx。

## 协议一：ScheduledTaskCreateRequest（POST /api/tasks）

六块（**无 notification 块**），完整示例与语义见 spec #1。校验规则：

- `task.name` 必填；`task.task_type` 当前仅 `AGENT_SCHEDULED_EXECUTION`。
- `target.agent_id` 必填，必须存在于 agents 且 enabled=1；`trigger_type` 当前仅 `HTTP_API`。
- `schedule.type` 当前仅 `CRON`；`expression` 必须是合法 Quartz 7 段 cron（用 Quartz CronExpression 解析校验）；`timezone` 必须 IANA 合法；`start_at`/`end_at` 可空、给出时为 ISO-8601。
- `execution.instruction` 必填；`parameters`、`context_refs` 可空对象/数组。
- `security.tenant_id` / `owner_user_id`：**仅作声明**，服务端以认证身份覆盖校验（见 05），不一致返回 403 `TENANT_MISMATCH`。
- `execution_policy.timeout_seconds` ≥ 1；`retry_count` ≥ 0；`max_concurrent_executions` ≥ 1。

创建响应：

```json
{ "task_id": "T9f2c…", "status": "ACTIVE", "agent_id": "inventory_risk_monitor",
  "next_execution_time": "2026-11-01T21:00:00+08:00", "current_version": 1 }
```

其他任务接口：

| 接口 | 行为 |
| --- | --- |
| GET /api/tasks/{id} | 详情：全字段 + current_version + next_execution_time |
| GET /api/tasks?tenant=&status= | 列表（tenant 由认证身份决定，status 可选过滤） |
| PUT /api/tasks/{id} | 全量更新六块 → 生成 current_version+1 的配置快照，下一触发生效；响应同创建 |
| POST /api/tasks/{id}/pause · /resume | ACTIVE↔PAUSED；PAUSED 不触发，Resume 立即重算 next |
| DELETE /api/tasks/{id} | status→STOPPED，Quartz 移除，记录保留；重复 STOPPED 幂等 |

## 协议二：AgentTriggerRequest（调度服务 → Agent 端点，POST）

```json
{
  "task_id": "T9f2c…", "execution_id": "E1a…", "task_config_version": 1,
  "trigger_type": "SCHEDULED", "trigger_time": "2026-11-01T21:00:00+08:00",
  "callback": {
    "result_mode": "EVENT_CALLBACK",
    "callback_endpoint": "/api/agent-executions/E1a…/result",
    "callback_token": "b3f1…64hex"
  }
}
```

`result_mode` 取自 Agent Registry。调用头：注入 registry.auth_headers（静态凭证）+ `Content-Type: application/json`。

**受理回执（Trigger Acknowledgement）**：EVENT_CALLBACK 模式下，Agent 在 ack 窗口内返回 2xx：

```json
{ "accepted": true, "execution_id": "E1a…" }
```

- 2xx 但 body 无 `accepted:true` 或 execution_id 不匹配 → 受理无效，按链路故障处理。
- ack 窗口（30s）未收到 2xx（连接失败/5xx/超时/拒绝）→ **链路故障**：attempt+1 重试，同一 execution_id，间隔 30s，最多 retry_count 次；穷尽后 Execution 置 FAILED（last_error 记录原因）。

**SYNC 模式**：一次 HTTP 调用，等待响应即结果；HTTP 客户端总超时 = `timeout_seconds`；返回 2xx 且 body 为 AgentExecutionResult → 落库；超时 → `TIMEOUT` 终态（不重试）；非 2xx → 链路故障（可重试）。

## 协议三：AgentExecutionResult（Agent → 调度服务；SYNC 响应体或 EVENT_CALLBACK 请求体）

```json
{
  "execution_id": "E1a…", "task_id": "T9f2c…",
  "status": "SUCCEEDED",
  "started_at": "2026-11-01T21:00:05+08:00", "finished_at": "2026-11-01T21:02:11+08:00",
  "summary": "一段话摘要",
  "business_events": [
    { "type": "INVENTORY_RISK", "severity": "HIGH", "title": "M001 缺料风险", "content": {}, "dedup_key": "M001-LOW_STOCK" }
  ],
  "report": {}
}
```

- `status` 仅 `SUCCEEDED` / `FAILED`；FAILED 时 business_events 可空、summary 说明原因。
- `business_events[].severity` ∈ `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`（排序即此序，min_severity 过滤用它）。
- `dedup_key` 可选：订阅去重键。
- EVENT_CALLBACK：`POST /api/agent-executions/{execution_id}/result`，头 `X-Callback-Token: <token>`。token 缺失/错误 → 403 `INVALID_CALLBACK_TOKEN`；有效首次回调 → 落库、转事件、返回 200 `{ "received": true }`；同 execution_id 再次回调（token 仍有效）→ 幂等返回首次结果对应 200，**不产生新事件**；execution 已终态（如 TIMEOUT）后回调 → 409 `EXECUTION_ALREADY_TERMINATED`（TIMEOUT 赢）。

## 事件信封（events 表与推送体同构）

```json
{
  "event_id": "Vc1…", "event_type": "EXECUTION_SUCCEEDED",
  "tenant_id": "TENANT001", "task_id": "T9f2c…", "execution_id": "E1a…",
  "severity": null, "occurred_at": "2026-11-01T21:02:11+08:00", "payload": {}
}
```

event_type 枚举（固定 7 个）：`EXECUTION_SUCCEEDED` / `EXECUTION_FAILED` / `EXECUTION_TIMEOUT` / `MISFIRE` / `SKIPPED_CONCURRENT` / `ESCALATED` / `BUSINESS_EVENT`。

- 生命周期事件（前 5 个）：payload 为 `{"summary": "…", "attempt": 1}` 摘要；MISFIRE / SKIPPED_CONCURRENT 无 execution_id（无执行发生），payload 附 `{"scheduled_fire_time": …}` 或 `{"running_execution_id": …}`。
- `BUSINESS_EVENT`：Agent 每条 business_event 单独成事件，severity 必填，payload = `{"type","title","content","dedup_key"}`；execution_id 有值。
- 事件产生即持久化，推送是投递层（event_deliveries），两阶段解耦。

## 订阅接口

- `POST /api/subscriptions`：`{ "callback_url": "https://…", "filter_task_ids": [] 可空, "filter_event_types": [] 可空, "min_severity": 可空, "dedup_window_seconds": 可空 }` → 响应含 `subscription_id` 与 **`secret`（仅此一次返回明文）**。
- `GET /api/subscriptions` 列表 / `DELETE /api/subscriptions/{id}` 停用（enabled=0，不删行）。
- 过滤语义：filter_task_ids 空数组/缺省 = 全部任务；filter_event_types 同理；min_severity 仅作用于 BUSINESS_EVENT（其余生命周期事件一律放行）。
