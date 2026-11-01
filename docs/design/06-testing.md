# 06 测试策略

只测外部行为，两条 HTTP 缝（spec 定案），SQLite 内存库，无模块内部 mock 单测。

## 缝一：对外 API（契约与状态推进）

- 工具：Spring Boot 测试上下文（随机端口或 MockMvc）+ 真实 Flyway 迁移后的 SQLite 内存库。
- 覆盖：401/403/租户隔离、agents CRUD（凭证不回显）、任务六块校验与生命周期（创建→next_execution_time→pause→resume→stop→幂等 stop）、订阅 CRUD（secret 仅返回一次）、执行/事件查询接口。

## 缝二：对端 HTTP（执行语义）

- stub Agent：测试源码内的 @RestController，路径 `/stub/agent/{scenario}`，scenario 控制行为：`sync-ok`（返回 SUCCEEDED+业务事件）、`sync-fail`（FAILED）、`sync-timeout`（挂起超过 timeout）、`ack-timeout`（不回执）、`http500`、`callback-ok`（回执后另线程回调）、`callback-dup`（重复回调）。
- stub Subscriber：`/stub/subscriber/{key}` 记录收到的请求（body/headers/次数），支持先 5xx 后 200 模拟恢复。
- 注册方式：测试 setup 里经 API（缝一）注册 agents（endpoint 指向本地 stub）与 subscriptions（callback 指向本地 stub）——**两缝互喂，不直插数据库**。
- Quartz：local profile 的 RAMJobStore + 秒级 cron（如 `*/1 * * * * ?`）+ Awaitility 等待；misfire 测试用极短阈值配置（如 0s）触发。
- 断言一律走接口（GET executions/events/任务详情），不直查数据库；数据一致性检查（#12 票）是唯一允许直查的脚本，属于验收工具而非测试。

## 命名与组织

- 测试类按缝分：`api.*Test`（缝一）、`semantics.*Test`（缝二）。
- 测试名用领域语言：`任务创建后返回下一次执行时间`、`上一轮未结束时跳过并记录 SKIPPED_CONCURRENT`。
- 一个测试一个行为断言簇；避免对实现细节（类名/内部方法/SQL）断言。

## 已知环境限制（如实报告，不造假）

- 本机无 Docker：MySQL 只验证 Flyway 脚本加载与语法（H2 MySQL 模式不可信，不采用），**MySQL 真库建表验证缺席**——写入测试报告的"未验证"一栏。
- 本地无 CI 平台：`scripts/smoke.sh`（编译+启动+401 冒烟）本地跑通即视为 #2 的 CI 冒烟验收；另附 GitHub Actions workflow 供推送后使用。
