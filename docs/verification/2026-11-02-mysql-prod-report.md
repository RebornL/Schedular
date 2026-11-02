# MySQL 真库与 prod profile 验证报告（WSL Docker）

日期：2026-11-02 · HEAD：`519ccf7` · 环境：Windows 11 + WSL2 Ubuntu-24.04 + Docker（mysql:8.4）+ Java 17

## 本次清零的"未验证"项

| 项 | 结果 |
| --- | --- |
| GitHub Actions CI | ✅ run 41325988874 success（push 触发） |
| Flyway MySQL 迁移（V1 业务 8 表 + V2 种子 + V3 Quartz） | ✅ 真库应用成功，schema v3 |
| prod profile 启动（MySQL + Quartz JDBC JobStore） | ✅ 启动 3.7s，日志确认 "supports persistence. and is clustered" |
| prod 全链路（注册 Agent → 建任务 → 触发 → 结果 → 事件） | ✅ 9/9 执行 SUCCEEDED，EXECUTION_SUCCEEDED + BUSINESS_EVENT(HIGH) 按对产生 |
| 暂停语义 | ✅ PAUSED 期间 next=null、零触发 |
| 停用语义 | ✅ STOPPED、next=null、历史保留 |

冒烟数据：任务 `T99584e52cbc543d6b1791efc11bff28d`（`*/2 * * * * ?`，Asia/Shanghai），stub Agent SYNC 模式。

## 验证发现并已修复的缺陷（4 个）

1. **Quartz 表结构缺失**（`fb1eeee`）：prod 启动报 `QRTZ_LOCKS/QRTZ_TRIGGERS doesn't exist`——Spring Boot 默认只对嵌入式库初始化 Quartz schema。新增 `V3__quartz_schema.sql`（官方 DDL，IF NOT EXISTS）。
2. **Quartz 集群假生效**（`76ce295`）：未配 `instanceId`，实例名恒为 NON_CLUSTERED。改 `AUTO`，重启后集群管理器真实工作（检测到旧实例失败并扫描未完成任务）。
3. **/api/agents 协议漂移**（`519ccf7`）：`AgentRequest`/`AgentView` 缺 `@JsonNaming(SnakeCase)`，只收驼峰；13 个测试文件随同修正为协议形状（69/69 回归全绿）。
4. 复审遗留确认：`julianday` 方言函数已全部清除（UTF-C UTC 时钟统一后字符串比较成立）。

## 环境备注（复现）

- 本机原有 Windows 版 mysqld 占用 3306/33060 → 容器发布到 **13306**：`docker run -d --restart unless-stopped --name schedular-mysql -e MYSQL_ROOT_PASSWORD=... -e MYSQL_DATABASE=schedular -p 13306:3306 mysql:8.4`
- WSL 虚拟机空闲回收会杀容器 → 保持一个常驻 `wsl.exe` 会话或依赖 `--restart unless-stopped`
- 启动命令：`SCHEDULAR_DB_URL='jdbc:mysql://127.0.0.1:13306/schedular' SCHEDULAR_DB_USER=root SCHEDULAR_DB_PASSWORD=... mvn spring-boot:run -Dspring-boot.run.profiles=prod`

## 仍未验证

- **双节点 Quartz 集群**：本次为单实例集群模式（JDBC 存储 + 集群标志 + ClusterManager 行为已实证），两个应用实例竞争触发的去重语义未实测——需要两份进程环境，建议部署时验证。
- MySQL 8.4 高于 Flyway 10.20 官测版本（8.1）——迁移与运行均正常，升级 Flyway 前留意告警。
