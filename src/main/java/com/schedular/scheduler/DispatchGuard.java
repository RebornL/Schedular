package com.schedular.scheduler;

import com.schedular.events.EventService;
import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DispatchGuard：建 Execution / 触发重试前的并发判定（docs/design/04 SKIPPED_CONCURRENT）。
 * 查 task 的 PENDING+RUNNING 数 ≥ max_concurrent_executions → 跳过并记 SKIPPED_CONCURRENT 事件
 * （payload 带 running_execution_id，取最早一条）。TaskJob 与 RetryJob 共用。
 */
@Component
public class DispatchGuard {

    private static final Logger log = LoggerFactory.getLogger(DispatchGuard.class);

    private final ExecutionRepository executionRepository;
    private final EventService eventService;

    public DispatchGuard(ExecutionRepository executionRepository, EventService eventService) {
        this.executionRepository = executionRepository;
        this.eventService = eventService;
    }

    /** @return true=放行；false=已跳过（事件已落库），调用方不得建行/触发 */
    public boolean tryAcquire(Task task, String excludeExecutionId) {
        List<Execution> active = executionRepository
                .findActiveByTask(task.taskId(), excludeExecutionId);
        if (active.size() < task.maxConcurrentExecutions()) {
            return true;
        }
        String runningExecutionId = active.get(0).executionId();
        log.warn("并发跳过: task_id={}, active_count={}, max={}, running_execution_id={}",
                task.taskId(), active.size(), task.maxConcurrentExecutions(), runningExecutionId);
        eventService.persist("SKIPPED_CONCURRENT", task.tenantId(), task.taskId(),
                null, null, Map.of("running_execution_id", runningExecutionId));
        return false;
    }
}
