# 02 数据模型

8 张表（spec 7 张 + `event_deliveries`，理由见 [README](./README.md) 细化 2）。JSON 一律 TEXT 列存 JSON 字符串。时间一律 TEXT 存 ISO-8601 带偏移。除 SQLite 自增 `id` 外，业务主键为 `TEXT`（前缀 + 32 位 UUID hex，去横线）。

## 表清单

### tasks
| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| task_id | TEXT | PK，`T` 前缀 |
| tenant_id / owner_user_id | TEXT | NOT NULL，来自认证身份（见 05） |
| permission_context_ref | TEXT | 可空 |
| name / description | TEXT | name NOT NULL |
| task_type | TEXT | NOT NULL，如 `AGENT_SCHEDULED_EXECUTION` |
| agent_id | TEXT | NOT NULL，须存在于 agents |
| agent_version | TEXT | 可空 |
| trigger_type | TEXT | NOT NULL，当前仅 `HTTP_API` |
| schedule_type | TEXT | NOT NULL，当前仅 `CRON` |
| cron_expression | TEXT | NOT NULL，Quartz 7 段 |
| timezone | TEXT | NOT NULL，IANA 名 |
| start_at / end_at | TEXT | 可空 |
| instruction | TEXT | NOT NULL |
| parameters | TEXT | JSON，可空 |
| context_refs | TEXT | JSON 数组，可空 |
| timeout_seconds | INT | NOT NULL |
| retry_count | INT | NOT NULL |
| max_concurrent_executions | INT | NOT NULL |
| current_version | INT | NOT NULL DEFAULT 1 |
| status | TEXT | NOT NULL：`ACTIVE` / `PAUSED` / `STOPPED` |
| consecutive_failures | INT | NOT NULL DEFAULT 0 |
| created_at / updated_at | TEXT | NOT NULL |

索引：(tenant_id, status)。

### task_config_versions
id INTEGER PK 自增；task_id TEXT NOT NULL；version INT NOT NULL；definition TEXT（创建/更新请求的完整六块 JSON 快照）；created_at。UNIQUE(task_id, version)。

### executions
| 列 | 说明 |
| --- | --- |
| execution_id | TEXT UNIQUE，`E` 前缀 |
| task_id / config_version | 关联任务与当时版本 |
| trigger_type | `SCHEDULED` |
| scheduled_fire_time | 计划触发时刻（misfire 判定依据） |
| attempt | INT，默认 1；链路重试 +1，同一 execution_id |
| status | `PENDING` → `RUNNING` → `SUCCEEDED` / `FAILED` / `TIMEOUT`（终态三选一） |
| result | TEXT JSON，AgentExecutionResult，可空 |
| summary | TEXT 可空（冗余 result.summary 便于查询） |
| last_error | TEXT 可空（链路故障描述） |
| callback_token | TEXT 可空，EVENT_CALLBACK 模式的一次性令牌 |
| started_at / finished_at / created_at | TEXT |

索引：(task_id, status)、(status)（并发判定与 sweeper 用）。

### events
event_id TEXT UNIQUE（`V` 前缀）；event_type TEXT NOT NULL；tenant_id TEXT NOT NULL；task_id / execution_id TEXT 可空；severity TEXT 可空；occurred_at TEXT NOT NULL；payload TEXT JSON。索引：(tenant_id, occurred_at)、(task_id)、(event_type)。

### event_deliveries
id PK；event_id TEXT NOT NULL；subscription_id INTEGER NOT NULL；status：`PENDING` → `DELIVERED` / `EXHAUSTED`，另有 `DEDUPED`（订阅去重命中时的留痕终态：窗口内同 dedup_key 已 DELIVERED 则不推送、直接落 DEDUPED 行，#8 引入）；attempts INT 默认 0；next_retry_at TEXT 可空；delivered_at TEXT 可空；last_status_code INT 可空；last_error TEXT 可空；created_at。UNIQUE(event_id, subscription_id)。索引：(status, next_retry_at)（sweeper 用）。

### subscriptions
id INTEGER PK；tenant_id TEXT NOT NULL；callback_url TEXT NOT NULL；secret TEXT NOT NULL（HMAC 密钥，生成于创建响应返回一次，库存哈希？v1 存明文、接口不回显——记入 05 的已知限制）；filter_task_ids / filter_event_types TEXT JSON 数组可空；min_severity TEXT 可空；dedup_window_seconds INT 可空；enabled INT 默认 1；created_at。

### agents
agent_id TEXT PK；agent_version TEXT 可空；trigger_type TEXT NOT NULL；endpoint_url TEXT NOT NULL；result_mode TEXT NOT NULL：`SYNC` / `EVENT_CALLBACK`；auth_headers TEXT JSON（静态注入头，**只写不读**：任何 API 响应不回显）；enabled INT 默认 1；created_at / updated_at。

### api_keys
id INTEGER PK；key_hash TEXT UNIQUE NOT NULL（SHA-256 hex，不存明文）；caller_name TEXT NOT NULL；role TEXT NOT NULL：`ADMIN` / `CALLER`（ADMIN 才可访问 /api/agents）；tenant_id TEXT 可空（CALLER 必填，绑定租户）；enabled INT 默认 1；created_at。

## 保留策略

无物理 DELETE。停用 = tasks.status=STOPPED；订阅停用 = enabled=0；registry 停用 = enabled=0。状态只前进：execution 终态不可回退；event_deliveries 不回退（EXHAUSTED 后仍允许 sweeper 忽略）。
