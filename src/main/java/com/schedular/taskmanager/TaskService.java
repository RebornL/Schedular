package com.schedular.taskmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.common.ApiException;
import com.schedular.scheduler.TaskScheduleManager;
import com.schedular.security.TenantContext;
import com.schedular.security.TenantPrincipal;
import com.schedular.storage.AgentRepository;
import com.schedular.storage.Task;
import com.schedular.storage.TaskConfigVersion;
import com.schedular.storage.TaskConfigVersionRepository;
import com.schedular.storage.TaskRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.quartz.CronExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 任务生命周期：创建校验+持久化+Quartz 注册、详情、列表、pause/resume、DELETE→STOPPED 幂等。 */
@Service
public class TaskService {

    private static final String TASK_TYPE = "AGENT_SCHEDULED_EXECUTION";
    private static final String TRIGGER_TYPE = "HTTP_API";
    private static final String SCHEDULE_TYPE = "CRON";

    private final TaskRepository taskRepository;
    private final TaskConfigVersionRepository configVersionRepository;
    private final AgentRepository agentRepository;
    private final TaskScheduleManager scheduleManager;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public TaskService(TaskRepository taskRepository,
                       TaskConfigVersionRepository configVersionRepository,
                       AgentRepository agentRepository,
                       TaskScheduleManager scheduleManager,
                       ObjectMapper objectMapper,
                       java.time.Clock clock) {
        this.taskRepository = taskRepository;
        this.configVersionRepository = configVersionRepository;
        this.agentRepository = agentRepository;
        this.scheduleManager = scheduleManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 六块校验结果：create/update 共用同一套校验逻辑。 */
    private record Validated(String tenantId, String ownerUserId, String agentId,
                             String cron, String timezone, String startAt, String endAt,
                             int timeoutSeconds, int retryCount, int maxConcurrentExecutions) {
    }

    public TaskView.Created create(TaskCreateRequest request) {
        Validated v = validated(request);
        String now = nowIso();
        Task task = new Task("T" + UUID.randomUUID().toString().replace("-", ""),
                v.tenantId(), v.ownerUserId(), request.task().permissionContextRef(),
                request.task().name(), request.task().description(), TASK_TYPE,
                v.agentId(), request.target().agentVersion(), TRIGGER_TYPE, SCHEDULE_TYPE,
                v.cron(), v.timezone(), v.startAt(), v.endAt(), request.execution().instruction(),
                toJson(request.execution().parameters()), toJson(request.execution().contextRefs()),
                v.timeoutSeconds(), v.retryCount(), v.maxConcurrentExecutions(),
                1, "ACTIVE", 0, now, now);
        taskRepository.insert(task);
        configVersionRepository.insert(new TaskConfigVersion(null, task.taskId(), 1,
                toJson(request), now));
        scheduleManager.register(task);
        return new TaskView.Created(task.taskId(), task.status(), task.agentId(),
                isoOrNull(scheduleManager.nextExecutionTime(task)), task.currentVersion());
    }

    /**
     * 全量更新：六块校验与创建一致 → 新快照（version+1）→ 更新 tasks 行 → 触发器重建。
     * 租户不可变更；STOPPED 不重建触发器；PAUSED 重建后保持暂停，恢复后按新版本执行。
     */
    public TaskView.Created update(String taskId, TaskCreateRequest request) {
        Task existing = findOwned(taskId);
        Validated v = validated(request);
        if (!v.tenantId().equals(existing.tenantId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_MISMATCH", "任务不可变更租户");
        }
        int version = existing.currentVersion() + 1;
        String now = nowIso();
        Task updated = new Task(taskId, existing.tenantId(), v.ownerUserId(),
                request.task().permissionContextRef(), request.task().name(),
                request.task().description(), TASK_TYPE, v.agentId(),
                request.target().agentVersion(), TRIGGER_TYPE, SCHEDULE_TYPE,
                v.cron(), v.timezone(), v.startAt(), v.endAt(), request.execution().instruction(),
                toJson(request.execution().parameters()), toJson(request.execution().contextRefs()),
                v.timeoutSeconds(), v.retryCount(), v.maxConcurrentExecutions(),
                version, existing.status(), existing.consecutiveFailures(),
                existing.createdAt(), now);
        taskRepository.update(updated);
        configVersionRepository.insert(new TaskConfigVersion(null, taskId, version,
                toJson(request), now));
        if (!"STOPPED".equals(updated.status())) {
            scheduleManager.reschedule(updated);
            if ("PAUSED".equals(updated.status())) {
                scheduleManager.pause(taskId);
            }
        }
        return new TaskView.Created(taskId, updated.status(), updated.agentId(),
                isoOrNull(scheduleManager.nextExecutionTime(updated)), version);
    }

    private Validated validated(TaskCreateRequest request) {
        if (request == null || isBlank(request.task()) || isBlank(request.target())
                || isBlank(request.schedule()) || isBlank(request.execution())
                || isBlank(request.executionPolicy())) {
            throw invalidRequest("六块结构不完整");
        }
        TenantPrincipal principal = requirePrincipal();

        String taskType = request.task().taskType();
        if (!TASK_TYPE.equals(taskType)) {
            throw invalidRequest("task_type 当前仅支持 " + TASK_TYPE);
        }
        if (isBlank(request.task().name())) {
            throw invalidRequest("task.name 必填");
        }
        String agentId = request.target().agentId();
        if (isBlank(agentId)) {
            throw invalidRequest("target.agent_id 必填");
        }
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AGENT_NOT_FOUND",
                        "agent_id 不存在于注册中心或已停用: " + agentId));
        if (!agent.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_NOT_FOUND",
                    "agent_id 不存在于注册中心或已停用: " + agentId);
        }
        if (!TRIGGER_TYPE.equals(request.target().triggerType()) && request.target().triggerType() != null) {
            throw invalidRequest("target.trigger_type 当前仅支持 " + TRIGGER_TYPE);
        }
        if (!SCHEDULE_TYPE.equals(request.schedule().type())) {
            throw invalidRequest("schedule.type 当前仅支持 " + SCHEDULE_TYPE);
        }
        String cron = request.schedule().expression();
        if (isBlank(cron)) {
            throw invalidRequest("schedule.expression 必填");
        }
        // Quartz cron：6 段（秒级）或 7 段（含可选年），文档示例 */1 * * * * ? 为 6 段
        int fields = cron.trim().split("\\s+").length;
        if (fields < 6 || fields > 7) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                    "cron 必须是 Quartz 6-7 段表达式（含秒）");
        }
        try {
            new CronExpression(cron.trim());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                    "非法的 Quartz cron 表达式: " + cron);
        }
        String timezone = request.schedule().timezone();
        if (isBlank(timezone)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "schedule.timezone 必填");
        }
        try {
            java.time.ZoneId.of(timezone);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE",
                    "非法 IANA 时区: " + timezone);
        }
        String startAt = parseInstant(request.schedule().startAt(), "start_at");
        String endAt = parseInstant(request.schedule().endAt(), "end_at");
        if (isBlank(request.execution().instruction())) {
            throw invalidRequest("execution.instruction 必填");
        }
        Integer timeout = request.executionPolicy().timeoutSeconds();
        Integer retry = request.executionPolicy().retryCount();
        Integer maxConcurrent = request.executionPolicy().maxConcurrentExecutions();
        if (timeout == null || timeout < 1) {
            throw invalidRequest("execution_policy.timeout_seconds 必须 ≥ 1");
        }
        if (retry == null || retry < 0) {
            throw invalidRequest("execution_policy.retry_count 必须 ≥ 0");
        }
        if (maxConcurrent == null || maxConcurrent < 1) {
            throw invalidRequest("execution_policy.max_concurrent_executions 必须 ≥ 1");
        }

        // tenant 以认证身份为准；声明值不一致 → 403；ADMIN（无租户绑定）可用声明值兜底，但不能凭空捏造
        String tenantId = principal.tenantId() != null ? principal.tenantId()
                : (request.security() != null ? request.security().tenantId() : null);
        if (isBlank(tenantId)) {
            throw invalidRequest("无法确定租户：认证身份未绑定租户且 security.tenant_id 缺失");
        }
        if (request.security() != null && !isBlank(request.security().tenantId())
                && !request.security().tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                    "security.tenant_id 与认证身份租户不一致");
        }
        String ownerUserId = request.security() != null ? request.security().ownerUserId()
                : principal.callerName();
        return new Validated(tenantId, ownerUserId, agentId, cron.trim(), timezone,
                startAt, endAt, timeout, retry, maxConcurrent);
    }

    public TaskView get(String taskId) {
        Task task = findOwned(taskId);
        return toView(task);
    }

    public List<TaskView> list(String status) {
        TenantPrincipal principal = requirePrincipal();
        return taskRepository.findByTenant(principal.tenantId(), status).stream()
                .map(this::toView).toList();
    }

    public TaskView pause(String taskId) {
        Task task = findOwned(taskId);
        if (!"PAUSED".equals(task.status())) {
            String now = nowIso();
            taskRepository.updateStatus(taskId, "PAUSED", now);
            scheduleManager.pause(taskId);
            task = withStatus(task, "PAUSED", now);
        }
        return toView(task);
    }

    public TaskView resume(String taskId) {
        Task task = findOwned(taskId);
        if ("STOPPED".equals(task.status())) {
            throw invalidRequest("已停用任务不可恢复");
        }
        if (!"ACTIVE".equals(task.status())) {
            String now = nowIso();
            taskRepository.updateStatus(taskId, "ACTIVE", now);
            scheduleManager.resume(taskId);
            task = withStatus(task, "ACTIVE", now);
        }
        return toView(task);
    }

    public TaskView stop(String taskId) {
        Task task = findOwned(taskId);
        if (!"STOPPED".equals(task.status())) {
            String now = nowIso();
            taskRepository.updateStatus(taskId, "STOPPED", now);
            scheduleManager.remove(taskId);
            task = withStatus(task, "STOPPED", now);
        }
        return toView(task);
    }

    private Task findOwned(String taskId) {
        TenantPrincipal principal = requirePrincipal();
        return taskRepository.findById(taskId)
                .filter(t -> principal.tenantId() == null || principal.tenantId().equals(t.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND",
                        "任务不存在: " + taskId));
    }

    private TaskView toView(Task task) {
        return new TaskView(task.taskId(), task.tenantId(), task.ownerUserId(),
                task.permissionContextRef(), task.name(), task.description(), task.taskType(),
                task.agentId(), task.agentVersion(), task.triggerType(), task.scheduleType(),
                task.cronExpression(), task.timezone(), task.startAt(), task.endAt(),
                task.instruction(), readTree(task.parameters()), readTree(task.contextRefs()),
                task.timeoutSeconds(), task.retryCount(), task.maxConcurrentExecutions(),
                task.currentVersion(), task.status(), task.consecutiveFailures(),
                task.createdAt(), task.updatedAt(),
                isoOrNull(scheduleManager.nextExecutionTime(task)));
    }

    private static Task withStatus(Task task, String status, String updatedAt) {
        return new Task(task.taskId(), task.tenantId(), task.ownerUserId(), task.permissionContextRef(),
                task.name(), task.description(), task.taskType(), task.agentId(), task.agentVersion(),
                task.triggerType(), task.scheduleType(), task.cronExpression(), task.timezone(),
                task.startAt(), task.endAt(), task.instruction(), task.parameters(), task.contextRefs(),
                task.timeoutSeconds(), task.retryCount(), task.maxConcurrentExecutions(),
                task.currentVersion(), status, task.consecutiveFailures(), task.createdAt(), updatedAt);
    }

    private TenantPrincipal requirePrincipal() {
        TenantPrincipal principal = TenantContext.get();
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "缺少认证身份");
        }
        return principal;
    }

    private String parseInstant(String value, String field) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw invalidRequest("schedule." + field + " 必须是 ISO-8601 时间");
        }
    }

    private JsonNode readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw invalidRequest("JSON 序列化失败");
        }
    }

    private static String isoOrNull(OffsetDateTime time) {
        return time == null ? null : time.toString();
    }

    private static ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isBlank(Object block) {
        return block == null;
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }
}
