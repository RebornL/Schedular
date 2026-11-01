# 05 安全

## API Key 鉴权（所有 /api/**）

- 头：`X-API-Key: <明文>`。服务端 SHA-256 hex 后查 api_keys.key_hash（常量时间比较），enabled=1 才通过，否则 401 `UNAUTHORIZED`。
- 角色：`ADMIN` 可访问 /api/agents/**；`CALLER` 访问 /api/tasks、/api/subscriptions、/api/events、/api/executions；所有角色过 401 这关。
- 租户绑定：CALLER 的 api_keys.tenant_id 即其唯一租户；请求路径/body 中的 tenant 仅作冗余，服务端一律以 TenantContext（来自 key）为准。
- 本地开发种子：V2 迁移插入一条 dev key（明文 `schedular-dev-key`，仅本地用；README 注明生产部署必须清理该行并发放正式 key）。

## tenant / owner 校验（不信任模型）

ScheduledTaskCreateRequest.security 声明的 tenant_id / owner_user_id：tenant_id 与 TenantContext 不符 → 403 `TENANT_MISMATCH`；owner_user_id 原样保存（平台无法验证用户级归属，记录声明值 + 认证 caller_name 一起审计）。查询/更新/删除/订阅/事件全部按 TenantContext 过滤，跨租户访问 → 404（不泄露存在性）。

查询口租户参数口径（TenantScope，适用于 GET /api/tasks、GET /api/subscriptions、GET /api/events 及任务单资源接口的 `tenant` 参数）：CALLER 恒按认证身份租户过滤，显式 tenant 不一致 → 403 `TENANT_MISMATCH`；ADMIN（无租户绑定）必须显式传 `?tenant=`，缺失 → 400 `MISSING_TENANT`。

## HMAC webhook 签名（调度服务 → 订阅方）

- 签名串：`<timestamp>.<rawBody>`（timestamp = Unix 秒）。
- 头：`X-Schedular-Timestamp: <timestamp>`、`X-Schedular-Signature: sha256=<HMAC-SHA256(secret, 签名串) hex>`。
- 时钟容差 ±5 分钟；订阅方必须同时校验时间戳与签名。
- secret 在创建订阅时生成（32 字节随机 hex），仅创建响应返回一次明文。

## Agent 调用凭证（调度服务 → Agent）

- registry.auth_headers JSON（如 `{"X-Internal-Token": "…"}`），每次触发注入请求头。
- **只写不读**：POST/PUT 接受，GET 列表/详情一律不返回该字段（响应模型不含该属性）。

## callback_token

- EVENT_CALLBACK 模式：触发前生成 32 字节随机 hex，存 executions.callback_token 并随 AgentTriggerRequest 下发。
- 校验：常量时间比较；缺失/错误 → 403 `INVALID_CALLBACK_TOKEN`。
- 重复回调：token 仍有效（Execution 未终态）时，同 execution_id 重复回调幂等返回 200、不产生新事件（首次生效）；Execution 已 TIMEOUT 终态后回调 → 409 `EXECUTION_ALREADY_TERMINATED`（TIMEOUT 赢）。

## 已知限制（v1 记录在案）

- subscription.secret DB 明文存储（推送需要原文计算 HMAC）；后续版本改加密存储。
- 无 OAuth/mTLS；API Key 为静态长期凭证，轮转靠发新 key + 停用旧 key。
