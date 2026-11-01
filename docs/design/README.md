# Schedular 设计文档

实现 spec（[#1](https://github.com/RebornL/Schedular/issues/1)）的工程契约。所有实现工单以本目录为唯一设计依据；术语遵循仓库根 `GLOSSARY.md`；通知解耦决策见 `docs/adr/0001-notify-via-subscription.md`。

## 文档索引

| 文档 | 内容 | 相关工单 |
| --- | --- | --- |
| [01-architecture.md](./01-architecture.md) | 模块划分、技术栈、profile、配置项 | #2 及所有 |
| [02-data-model.md](./02-data-model.md) | 8 张表结构、状态枚举、保留策略 | #2 及所有 |
| [03-protocols.md](./03-protocols.md) | 三协议、事件信封、受理回执、错误体 | #4 #5 #7 #9 |
| [04-execution-semantics.md](./04-execution-semantics.md) | Execution 状态机、misfire / 并发 / 重试 / 升级 | #5 #8 #9 #10 #11 |
| [05-security.md](./05-security.md) | API Key、租户校验、HMAC、callback token、凭证 | #2 #3 #7 |
| [06-testing.md](./06-testing.md) | 两条测试缝、stub 设计、时间控制 | 所有 |

## 对 spec 的三处细化（已按工程约束敲定）

1. **存储层用 Spring JDBC（JdbcTemplate），不用 JPA**：SQLite 无官方 Hibernate 方言，避免社区方言风险；Flyway 迁移不变，JSON 列一律 TEXT 存 JSON 字符串（Jackson 序列化）。
2. **第 8 张表 `event_deliveries`**：spec 列了 7 张表；webhook 推送重试要"按节奏发生且可观测"，必须持久化投递状态（重启不丢），故增加投递记录表。
3. **result_mode 放 Agent Registry（SYNC / EVENT_CALLBACK）**：SYNC = 触发调用内直接返回 Execution Result（超时窗口 = timeout_seconds）；EVENT_CALLBACK = 快速返回受理回执（ack 超时 30s），随后凭一次性 token 回调。**ack 超时属于触发链路故障（可重试）；timeout_seconds 超时属于执行超时（TIMEOUT 终态，不重试）**——两类超时分离。

## 构建与运行

- Java 17、Maven 3.9.9（本机 `D:\tools\apache-maven-3.9.9\bin\mvn`，仓库同时带 Maven Wrapper）
- 本地：`mvn spring-boot:run -Dspring-boot.run.profiles=local`（SQLite）
- 测试：`mvn test`（SQLite 内存 + stub）
- 种子 API Key：V2 迁移插入 `schedular-dev-key`（ADMIN）与 `schedular-dev-caller`（CALLER），**仅本地开发使用，生产部署必须清理这两行并发放正式 key**。
