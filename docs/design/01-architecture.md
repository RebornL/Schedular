# 01 架构

## 总体形态

单 Spring Boot 3.4.x 服务（Java 17，Maven 单模块，`com.schedular` groupId=`com.schedular` artifactId=`schedular`），内部五部分，只作包边界不作网络边界：

```
com.schedular
├── SchedularApplication
├── common/        # ApiError、异常、JsonUtil（Jackson 封装）、Ids（前缀+UUID）、Clock Bean
├── security/      # ApiKeyFilter（OncePerRequestFilter）、TenantContext、HmacSigner
├── storage/       # repository 接口 + JdbcTemplate 实现 + 领域 record
├── taskmanager/   # TaskService、AgentRegistryService、SubscriptionService、ExecutionQueryService
│                  # controllers：TaskController / AgentController / SubscriptionController / ExecutionQueryController / EventController
├── scheduler/     # QuartzConfig、TaskJob（到点入口）、DispatchGuard（misfire/并发判定）
├── triggerclient/ # AgentTriggerClient（出站 HTTP、ack/result 解析、两类超时）、CallbackController（结果回调接收）
└── events/        # EventService（事件持久化）、EventPusher（订阅匹配 + HMAC 推送 + 重试）、DeliverySweeper
```

调用方向（单向，禁止反向依赖）：

```
controllers → taskmanager 服务层 → storage
scheduler.TaskJob → DispatchGuard → taskmanager(ExecutionService) → triggerclient → taskmanager(ExecutionService) → events.EventService → events.EventPusher
triggerclient.CallbackController → taskmanager(ExecutionService) → events.EventService
```

## 技术栈（锁定）

| 项 | 选择 | 理由 |
| --- | --- | --- |
| Web | spring-boot-starter-web（MVC，非 WebFlux） | 简单同步 IO 场景 |
| 数据访问 | spring-boot-starter-jdbc（JdbcTemplate） | SQLite 无官方 Hibernate 方言 |
| 迁移 | Flyway（+ `flyway-mysql`、`flyway-community-db-support` 提供 SQLite 支持） | 双库迁移 |
| 调度 | spring-boot-starter-quartz | 集群 JDBC / RAMJobStore 双 profile |
| 校验 | spring-boot-starter-validation | 协议校验 |
| JSON | Jackson（随 web） | payload TEXT ↔ JsonNode |
| 驱动 | sqlite-jdbc、mysql-connector-j | 双 profile |
| 测试 | spring-boot-starter-test、awaitility | 缝测试 + 异步等待 |

Spring Boot 版本固定 3.4.5。**禁止额外引入**：JPA、Lombok（手写样板）、消息队列、Spring Cloud。

## Profile

- `local`（默认激活）：SQLite（内存 `jdbc:sqlite::memory:` 需共享连接或用文件 `./schedular-local.db`，二选一由配置定）+ Quartz RAMJobStore + `spring.quartz.auto-startup=true`。
- `prod`：MySQL + Quartz JDBC JobStore 集群（`org.quartz.jobStore.isClustered=true`）。
- Flyway 迁移：`db/migration` 通用目录，SQLite 与 MySQL 方言差异用两套目录 `db/migration/sqlite` 与 `db/migration/mysql`（Flyway locations 按 profile 配置），DDL 语义保持一致。

## 关键配置项（application.yml，均可覆盖）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `schedular.ack-timeout-seconds` | 30 | EVENT_CALLBACK 模式等待受理回执的窗口，超时属链路故障→重试 |
| `schedular.misfire-threshold-seconds` | 60 | 触发时刻与计划触发时刻之差超过该值 → MISFIRE 跳过 |
| `schedular.push.retry-delays` | 30s,5m,30m | webhook 推送失败重试节奏 |
| `schedular.push.sweeper-interval` | 30s | 投递扫描周期（重启后恢复 PENDING 投递） |
| `schedular.escalation.threshold` | 3 | 连续失败升级阈值 |
| `schedular.dispatch.threads` | 8 | 出站触发线程池 |

## 依赖不变量

- 所有 SQL 只经 storage 包的 repository 发出；业务代码不得持有 JdbcTemplate。
- 没有任何物理 DELETE（spec：记录永不删除）；停用/穷尽都是状态推进。
- 时钟统一注入 `Clock` Bean（测试可替换）；时间戳格式 `yyyy-MM-dd'T'HH:mm:ssXXX`（ISO-8601 带时区偏移）。
