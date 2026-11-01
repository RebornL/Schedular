# Schedular — Agent 定时调度

为 ERP Agent 平台提供定时任务调度：主 Agent 注册任务，调度服务到点触发目标 Agent，执行结果以事件分发给订阅方。

## Language

**Task（任务）**:
一次定时调度的计划：目标 Agent、执行计划、任务参数与安全上下文。由 `task_id` 标识。
_Avoid_: 作业、Job

**Task Definition（任务定义）**:
创建 Task 时提交的结构化描述，含 task / target / schedule / execution / security / execution_policy 六部分；不含通知策略。

**Execution（执行）**:
Task 的一次实际运行。一个 Task 产生多个 Execution，每次运行由 `execution_id` 标识。
_Avoid_: 运行记录、触发记录

**Agent Registry（Agent 注册中心）**:
agent_id 到触发端点与调用方式的登记表；调度服务据此调用目标 Agent，主 Agent 不接触真实 URL。
_Avoid_: 服务发现

**Trigger（触发）**:
调度服务到点对目标 Agent 发起的一次调用，携带 `task_id` 与 `execution_id`。

**Trigger Acknowledgement（受理回执）**:
Agent 收到 Trigger 后立即返回的受理响应（接受/拒绝 + execution_id），不承载执行结果；执行结果经 Execution Result 另行交回。
_Avoid_: 触发结果

**Execution Result（执行结果）**:
Agent 对一次 Execution 的返回：执行状态、分析结论、业务事件与报告。

**Event（事件）**:
调度服务产生的事实记录（触发、跳过、misfire、执行结果、业务事件），持久化后分发给订阅方。
_Avoid_: 消息、通知

**Business Event（业务事件）**:
Execution Result 中携带的业务层事件（如 INVENTORY_RISK），带严重级别（severity），区别于调度生命周期事件。

**Subscription（订阅）**:
订阅方向调度服务登记的事件接收关系：过滤条件（任务、事件类型、最低严重级别）加回调端点。替代任务定义内的通知策略。
_Avoid_: 通知策略、推送配置

**Subscriber（订阅方）**:
通过订阅接口接收 Event 的外部系统（主 Agent、通知中心、其他应用），通知效果由订阅方自行实现。

**Main Agent（主 Agent）**:
解析用户意图、调用 Task API 创建 Task 的上游 Agent。
