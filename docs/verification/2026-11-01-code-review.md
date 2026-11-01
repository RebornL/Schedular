# 代码审查报告（工单 #13）

- 日期：2026-11-01
- 范围：ticket-13 @ 90082ec，双轴独立审查（Standards 轴 + Spec 轴）
- 复验：修复后全量 `mvn test`，结果见文末。

## 一、双轴原始发现（原样引用）

【Spec 轴】(a)1 EVENT_CALLBACK 执行超时无强制：无扫描超时 RUNNING 执行的 sweeper，Agent 受理后永不回调时 Execution 永远 RUNNING——不标 TIMEOUT、槽位不释放、不计升级。(a)2 owner_user_id 未按认证身份校验（备案，见下）。(a)3 permission_context_ref 位置偏移：spec 在 security 块，实现在 task 块。(b)1 ESCALATED 计数≥3 每次失败重发——按 04 设计文档属有意行为（dedup 窗口压制），非缺陷。(c)1 GET /api/tasks 忽略 ?tenant= 参数，ADMIN 无法按租户列表（并入 B2）。(c)2 去重窗口 ISO 字典序比较（备案，见下）。

【Standards 轴】硬违规：H1 AgentTriggerClient.java:47 OffsetDateTime.now() 未注入 Clock（01 架构"时钟统一注入"）；H2 T/E/V 前缀 ID 三处内联重复，common/Ids 未实现（01 架构）；H3 DEDUPED 状态未记入 02-data-model；H4 事件 payload 与 03-protocols 枚举不符且文档未记录差异；H5 SubscriptionService.list/TaskService.findOwned 对 ADMIN(null tenant) 放行全部租户（05"查询全部按 TenantContext 过滤"）；H6 HMAC 在 events/EventPusher 实现，01 规定 security/HmacSigner。基线 smell：EventPusher 两段重复更新逻辑（Duplicated Code）；ApiKeyFilter.writeError 手拼 JSON 与 ApiError 形状重复（修复时顺手改用 ObjectMapper 序列化统一形状）；失败路径双重 update 可合并（并入 F4）。

## 二、逐条处置表

| # | 级别 | 发现 | 处置 | 主要文件 |
| --- | --- | --- | --- | --- |
| B1/(a)1 | BLOCKER | EVENT_CALLBACK 执行超时无 sweeper | 已修：新增 scheduler/ExecutionTimeoutSweeper（fixedDelay=`schedular.execution-timeout-sweep-interval:30s`），扫 RUNNING 且 started_at+tasks.timeout_seconds 过期的行；`finalizeTimeoutIfRunning` 带 `WHERE status='RUNNING'` 原子置 TIMEOUT（与回调竞态只有一方生效），走既有终态路径（EXECUTION_TIMEOUT 事件 + 连续失败计数 + last_error=执行超时（未收到回调）） | ExecutionTimeoutSweeper.java（新增）、ExecutionRepository.java、ExecutionService.java、application.yml |
| B2/H5/(c)1 | BLOCKER | ADMIN 空租户跨租户放行；三个查询口口径不一 | 已修：新增 security/TenantScope 统一口径——CALLER 恒按认证租户（显式不一致 → 403 TENANT_MISMATCH）、ADMIN 必须显式传 `?tenant=`（缺失 → 400 MISSING_TENANT）；GET /api/tasks、GET /api/subscriptions、GET /api/events 及任务单资源接口统一接入 | TenantScope.java（新增）、TaskService/TaskController、SubscriptionService/SubscriptionController、EventQueryController |
| B3/(a)3 | BLOCKER | permission_context_ref 协议位置在 task 块 | 已修：移回 TaskCreateRequest.SecurityBlock，TaskService 校验/落库改读 security 块；tasks 表列不变 | TaskCreateRequest.java、TaskService.java |
| F1/H1 | 小修 | AgentTriggerClient 直用 OffsetDateTime.now() | 已修：注入 common/ Clock Bean | AgentTriggerClient.java |
| F2/H2 | 小修 | T/E/V 前缀 ID 三处内联重复 | 已修：新增 common/Ids，TaskService/ExecutionService/EventService 全部替换 | Ids.java（新增）、TaskService/ExecutionService/EventService |
| F3/H6 | 小修 | HMAC 在 EventPusher 内部实现 | 已修：抽到 security/HmacSigner，EventPusher 改调用；签名串格式不变 | HmacSigner.java（新增）、EventPusher.java |
| F4/基线 smell | 小修 | EventPusher 重复更新逻辑、失败路径双重 update | 已修：attempt/markFailedOnException 合并为单一 recordFailure 私有方法，PENDING/EXHAUSTED 一次 update 完成；markFailedOnException 删除 | EventPusher.java |
| 基线 smell | 小修 | ApiKeyFilter.writeError 手拼 JSON | 已修：注入 ObjectMapper，按 ApiError 形状序列化 | ApiKeyFilter.java、SecurityConfig.java |
| D1/H3 | 文档 | DEDUPED 未记入 02 | 已修：02-data-model.md event_deliveries.status 增补 DEDUPED | 02-data-model.md |
| D2/H4 | 文档 | 事件 payload 与实现不符 | 已修：03-protocols.md 按类型精确化 payload | 03-protocols.md |
| D3 | 文档 | HmacSigner 归位现状 | 已修：01-architecture.md security 节与依赖不变量注明；顺带补 ExecutionTimeoutSweeper 与新配置键 | 01-architecture.md |
| (b)1 | 备案 | ESCALATED ≥3 每次失败重发 | 不修：04 设计文档明确为有意行为（重复告警由订阅 dedup_window 压制） | — |
| (a)2 | 备案 | owner_user_id 不按认证身份校验 | 不修：05 已记录 v1 限制（平台无法验证用户级归属，记录声明值 + caller_name 审计）；api_keys 无 owner 绑定属结构性缺口，留待 v1.1 | — |
| (c)2 | 复审已修 | 去重窗口 ISO 字符串字典序比较 | 已修（复审追加）：Clock 固定 UTC、since 归一化 Instant，字典序比较完全成立，撤销原备案 | ClockConfig、EventRepository、EventQueryController |

## 三、备案两条（理由）

1. **owner_user_id 不按认证身份校验**：docs/design/05 已记录 v1 限制——平台无法验证用户级归属，记录声明值 + caller_name 审计；api_keys 表无 owner 绑定属结构性缺口，留待 v1.1。
2. ~~去重窗口 ISO 字符串字典序比较~~：复审阶段已随 MySQL 方言修复撤销（Clock 固定 UTC + since 归一化，见下"五、复审追加"）。

## 四、复验结果

- 全量 `mvn -B test`：**Tests run: 69, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**（既有 65 例全绿 + 新增 4 例，另在 DedupQuerySemanticsTest 补 +08:00 偏移 since 查询断言）。
- 新增测试：TenantScopeApiTest（3 例：ADMIN 无参 400 / ADMIN 带 tenant 只见该租户 / CALLER 他人 tenant 403）、ExecutionTimeoutSweeperTest（1 例：ack 后不回调 → TIMEOUT 落库 + EXECUTION_TIMEOUT 事件 + 槽位释放）。

## 五、复审追加（MySQL 方言修复）

复审发现首轮修复引入/沿留方言炸弹：`EventRepository.search` 与 `ExecutionRepository.findTimeoutRunning` 用 SQLite 专属 `julianday`，MySQL prod profile 必然报错。处置（commit 追加）：

1. `ClockConfig` 改为 `Clock.systemUTC()`：落库时间戳一律 UTC ISO `Z` 字符串，字典序比较完全成立（同步撤销 (c)2 备案）。
2. `EventRepository.search`：`julianday` 比较改字符串比较 `occurred_at >= ?`；`EventQueryController` 将 since 归一化为 `OffsetDateTime.parse(since).toInstant().toString()`（恒 Z 结尾，任意偏移入参正确）。
3. `ExecutionRepository.findTimeoutRunning`：SQL 只做 `status='RUNNING' AND started_at <= cutoff(now-1s)` 粗过滤，timeout_seconds 精确判定移至 sweeper Java 侧（RUNNING 行数量级小，v1 取舍已注明 javadoc）。
4. 仓库内无第三处 `julianday`/`strftime`（grep 确认，含首轮新增 SQL）。
5. 新增测试：DedupQuerySemanticsTest 补 `?since=` 带 `+08:00` 偏移入参查询正确一例；01-architecture 补"Clock 固定 UTC / 持久化时间戳一律 UTC"不变量。

复验：全量 `mvn -B test` **Tests run: 69, Failures: 0, Errors: 0 — BUILD SUCCESS**。
