# Schedular — Agent 定时调度服务

为 ERP Agent 平台提供统一的定时任务调度：主 Agent 注册 **Task**，Quartz 到点 **Trigger** 目标 Agent（经 Agent Registry），执行结果变成 **Event** 按订阅推送给 **Subscriber**。单 Spring Boot 3.4.5 服务（Java 17 / Maven），通知渠道由订阅方自治实现（[ADR-0001](docs/adr/0001-notify-via-subscription.md)）。

领域术语见 [GLOSSARY.md](GLOSSARY.md)；设计契约见 [docs/design/](docs/design/README.md)。

## 快速开始

```bash
# 本地（SQLite + Quartz RAMJobStore，无外部依赖）
mvn spring-boot:run -Dspring-boot.run.profiles=local    # http://127.0.0.1:8080
```

所有 `/api/**` 需要 `X-API-Key` 请求头。本地种子 key（迁移 V2，**生产必须清理后发放正式 key**）：

| key | 角色 | 租户 |
| --- | --- | --- |
| `schedular-dev-key` | ADMIN | 无（访问 /api/agents 等管理口） |
| `schedular-dev-caller` | CALLER | `tenant-dev`（任务/订阅/事件/执行） |

### 生产（MySQL + Quartz JDBC 集群）

```bash
export SCHEDULAR_DB_URL='jdbc:mysql://127.0.0.1:13306/schedular'
export SCHEDULAR_DB_USER=root SCHEDULAR_DB_PASSWORD='...'
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

- Flyway 双方言迁移自动建齐 8 张业务表 + 11 张 QRTZ_ 表（[V3 说明](src/main/resources/db/migration/mysql/V3__quartz_schema.sql)）
- 集群：`instanceId=AUTO` + JDBC JobStore，多实例直接水平扩展（双节点竞争语义建议部署时实测）
- 本机 WSL + Docker 快速起 MySQL：`docker run -d --restart unless-stopped --name schedular-mysql -e MYSQL_ROOT_PASSWORD=... -e MYSQL_DATABASE=schedular -p 13306:3306 mysql:8.4`（注意 Windows 原生 mysqld 可能占用 3306）

## 接口面

| 接口 | 说明 |
| --- | --- |
| `POST /api/tasks` | 创建定时任务（六块结构：task / target / schedule / execution / security / execution_policy，**无 notification 块**） |
| `GET /api/tasks/{id}` · `GET /api/tasks?tenant=&status=` | 详情（含 next_execution_time）· 列表 |
| `PUT /api/tasks/{id}` | 更新 → 新配置版本，下一触发生效，历史执行保留旧版本引用 |
| `POST /api/tasks/{id}/pause` · `/resume` · `DELETE` | 暂停 / 恢复 / 停用（保留历史） |
| `POST /api/subscriptions` | 订阅：回调 URL + 过滤（task_ids / event_types / min_severity）+ 去重窗口；secret 仅创建时返回一次 |
| `GET /api/events` | 事件查询（订阅方拉历史兜底） |
| `GET /api/tasks/{id}/executions` · `GET /api/executions/{id}` | 执行记录 |
| `CRUD /api/agents` | Agent Registry（仅 ADMIN；auth_headers 只写不读） |
| `POST /api/agent-executions/{id}/result` | Agent 异步结果回调（`X-Callback-Token` 鉴权） |

## 三协议与事件

1. **ScheduledTaskCreateRequest**：主 Agent → Task API，六块结构；`agent_id` 查 Registry 得真实端点，主 Agent 不接触 URL
2. **AgentTriggerRequest**：调度服务 → Agent，只传 `task_id` / `execution_id` / 版本 / 触发时间 / 一次性 `callback_token`；Agent 立即返回受理回执；结果可同步返回或 EVENT_CALLBACK（重复回调幂等）
3. **AgentExecutionResult**：状态 / 摘要 / 业务事件 / 报告；每条业务事件单独成 `BUSINESS_EVENT` 推送

事件类型（7 个）：`EXECUTION_SUCCEEDED` `EXECUTION_FAILED` `EXECUTION_TIMEOUT` `MISFIRE` `SKIPPED_CONCURRENT` `ESCALATED` `BUSINESS_EVENT`。订阅推送带 `X-Schedular-Signature: sha256=HMAC(secret, "<ts>.<body>")` + 时间戳头；失败重试 3 次（30s/5m/30m），穷尽后走查询接口兜底。

关键执行语义：misfire 跳过不补跑；并发上限跳过不排队；执行超时（TIMEOUT）不计入重试、槽位立即释放；连续失败 ≥3 产生 `ESCALATED`（成功清零）；触发链路故障按 `retry_count` 重试（同一 execution_id，Agent 幂等兜底）；记录永不物理删除。

## 验证

```bash
mvn test               # 全量测试（两条 HTTP 缝：对外 API + 对端 stub Agent/Subscriber，SQLite 内存）
bash scripts/smoke.sh  # 编译 → 启动 → 401 冒烟
bash scripts/verify.sh # 全量测试 + 数据一致性检查（8 项不变量）+ 生成报告
```

历史验收报告见 [docs/verification/](docs/verification/)。

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [docs/design/](docs/design/README.md) | 架构、数据模型、协议、执行语义、安全、测试策略 |
| [GLOSSARY.md](GLOSSARY.md) | 领域词汇（Task / Execution / Event / Subscription…） |
| [docs/adr/](docs/adr/) | 决策记录（通知解耦为订阅模型等） |
| [docs/verification/](docs/verification/) | E2E 验收报告、代码审查报告、MySQL 真库验证报告 |
| [Issues](https://github.com/RebornL/Schedular/issues) | 工单与规格（#1 为总规格） |

## 已知限制（v1）

- `owner_user_id` 不做认证校验（平台无法验证用户级归属，记录声明值 + 调用方审计）；订阅 secret 明文存储
- 无管理后台 / OAuth / 消息队列接入（`trigger_type` 仅 `HTTP_API`）
- Quartz 双节点竞争触发去重未实测（单实例集群行为已验证）
