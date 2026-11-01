package com.schedular.taskmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.events.EventService;
import com.schedular.scheduler.RetryScheduler;
import com.schedular.storage.Agent;
import com.schedular.storage.AgentRepository;
import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import com.schedular.triggerclient.AgentExecutionResult;
import com.schedular.triggerclient.AgentTriggerClient;
import com.schedular.triggerclient.TriggerOutcome;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Execution 主链路：建行（PENDING）→ 触发 Agent → 受理回执/同步结果处理 → 终态落库 → 事件。
 * 链路故障：last_error 记录、状态回 PENDING，attempt 未穷尽时安排 30s 一次性重试（#10）。
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionRepository executionRepository;
    private final AgentRepository agentRepository;
    private final AgentTriggerClient triggerClient;
    private final EventService eventService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RetryScheduler retryScheduler;

    public ExecutionService(ExecutionRepository executionRepository,
                            AgentRepository agentRepository,
                            AgentTriggerClient triggerClient,
                            EventService eventService,
                            ObjectMapper objectMapper,
                            Clock clock,
                            RetryScheduler retryScheduler) {
        this.executionRepository = executionRepository;
        this.agentRepository = agentRepository;
        this.triggerClient = triggerClient;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.retryScheduler = retryScheduler;
    }

    /** Quartz 到点入口：建 Execution 行并立即触发；任何链路异常只记录，不上抛。 */
    public void createAndDispatch(Task task, OffsetDateTime scheduledFireTime) {
        String executionId = "E" + UUID.randomUUID().toString().replace("-", "");
        boolean callbackMode = !"SYNC".equals(
                agentRepository.findById(task.agentId()).map(Agent::resultMode).orElse("SYNC"));
        Execution execution = new Execution(
                executionId, task.taskId(), task.currentVersion(), "SCHEDULED",
                scheduledFireTime.toString(), 1, Execution.STATUS_PENDING,
                null, null, null,
                callbackMode ? UUID.randomUUID().toString().replace("-", "") : null,
                null, null, OffsetDateTime.now(clock).toString());
        executionRepository.insert(execution);
        dispatch(execution, task);
    }

    /** 触发入口：TaskJob 建行后与 RetryJob 重试共用；任何链路异常只记录，不上抛。 */
    public void dispatch(Execution execution, Task task) {
        Agent agent = agentRepository.findById(task.agentId())
                .filter(Agent::enabled)
                .orElse(null);
        if (agent == null) {
            String error = "Agent 不存在或已停用: " + task.agentId();
            log.warn("链路故障: execution_id={}, {}", execution.executionId(), error);
            handleLinkFailure(execution, task, error);
            return;
        }
        // PENDING → RUNNING：发出请求前推进
        executionRepository.markRunning(execution.executionId(), nowIso());
        TriggerOutcome outcome;
        try {
            outcome = triggerClient.trigger(task, agent, execution);
        } catch (RuntimeException e) {
            log.error("触发链路异常: execution_id={}", execution.executionId(), e);
            handleLinkFailure(execution, task, "触发链路异常: " + e.getMessage());
            return;
        }
        switch (outcome.type()) {
            case ACK -> log.info("受理回执有效，等待回调: execution_id={}", execution.executionId());
            case RESULT -> handleAgentResult(execution, task, outcome.result());
            case TIMEOUT -> finalizeTerminal(execution, task, Execution.STATUS_TIMEOUT,
                    null, "执行超时: " + outcome.error());
            case LINK_FAILURE -> {
                log.warn("链路故障: execution_id={}, error={}", execution.executionId(), outcome.error());
                handleLinkFailure(execution, task, outcome.error());
            }
        }
    }

    /**
     * 链路故障（docs/design/04 链路重试）：状态回 PENDING 并记录 last_error；
     * attempt < 1+retry_count → 安排一次性重试；穷尽 → FAILED 终态 + EXECUTION_FAILED 事件。
     */
    private void handleLinkFailure(Execution execution, Task task, String error) {
        executionRepository.recordLinkFailure(execution.executionId(), error);
        if (execution.attempt() < 1 + task.retryCount()) {
            retryScheduler.scheduleRetry(task.taskId(), execution.executionId(),
                    execution.attempt() + 1);
            return;
        }
        failExhausted(execution, task, "重试穷尽: attempt=" + execution.attempt()
                + ", retry_count=" + task.retryCount() + ", last_error=" + error);
    }

    /** 穷尽/中止的 FAILED 终态：last_error 记原因，终态不可回退。 */
    public void failExhausted(Execution execution, Task task, String reason) {
        log.warn("链路重试终局: execution_id={}, {}", execution.executionId(), reason);
        executionRepository.finalizeFailed(execution.executionId(), reason, nowIso());
        emitLifecycleEvent(task, execution, Execution.STATUS_FAILED, reason);
    }

    /** 2xx + AgentExecutionResult（sync 响应与 EVENT_CALLBACK 回调共用）：业务 SUCCEEDED/FAILED 均为终态，FAILED 不重试。 */
    public void handleAgentResult(Execution execution, Task task, AgentExecutionResult result) {
        String status = "SUCCEEDED".equals(result.status())
                ? Execution.STATUS_SUCCEEDED : Execution.STATUS_FAILED;
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentExecutionResult 序列化失败", e);
        }
        executionRepository.finalizeTerminal(execution.executionId(), status, resultJson,
                result.summary(), nowIso());
        emitLifecycleEvent(task, execution, status, result.summary());
        emitBusinessEvents(task, execution, result);
    }

    private void finalizeTerminal(Execution execution, Task task, String status,
                                  String resultJson, String summary) {
        executionRepository.finalizeTerminal(execution.executionId(), status, resultJson,
                summary, nowIso());
        emitLifecycleEvent(task, execution, status, summary);
    }

    private void emitLifecycleEvent(Task task, Execution execution, String status, String summary) {
        String eventType = switch (status) {
            case Execution.STATUS_SUCCEEDED -> "EXECUTION_SUCCEEDED";
            case Execution.STATUS_FAILED -> "EXECUTION_FAILED";
            case Execution.STATUS_TIMEOUT -> "EXECUTION_TIMEOUT";
            default -> throw new IllegalStateException("非法终态: " + status);
        };
        Map<String, Object> payload = new HashMap<>();
        payload.put("summary", summary == null ? status : summary);
        payload.put("attempt", execution.attempt());
        eventService.persist(eventType, task.tenantId(), task.taskId(),
                execution.executionId(), null, payload);
    }

    private void emitBusinessEvents(Task task, Execution execution, AgentExecutionResult result) {
        if (result.businessEvents() == null) {
            return;
        }
        for (AgentExecutionResult.BusinessEvent be : result.businessEvents()) {
            if (be.severity() == null || be.severity().isBlank()) {
                log.warn("业务事件缺 severity，丢弃: execution_id={}, type={}",
                        execution.executionId(), be.type());
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", be.type());
            payload.put("title", be.title());
            payload.put("content", be.content());
            payload.put("dedup_key", be.dedupKey());
            eventService.persist("BUSINESS_EVENT", task.tenantId(), task.taskId(),
                    execution.executionId(), be.severity(), payload);
        }
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }
}
