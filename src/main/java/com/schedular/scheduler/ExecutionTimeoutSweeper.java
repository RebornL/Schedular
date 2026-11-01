package com.schedular.scheduler;

import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import com.schedular.taskmanager.ExecutionService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EVENT_CALLBACK 执行超时兜底（04 状态机"ack 后 timeout_seconds 内未回调 → TIMEOUT"）：
 * Agent 受理后永不回调时，扫 status='RUNNING' 且 started_at + tasks.timeout_seconds < now 的行，
 * 原子置 TIMEOUT（WHERE status='RUNNING'，与回调竞态只有一方生效）并走既有终态路径。
 */
@Component
public class ExecutionTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimeoutSweeper.class);

    static final String TIMEOUT_REASON = "执行超时（未收到回调）";

    private final ExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final ExecutionService executionService;
    private final Clock clock;

    public ExecutionTimeoutSweeper(ExecutionRepository executionRepository,
                                   TaskRepository taskRepository,
                                   ExecutionService executionService, Clock clock) {
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.executionService = executionService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${schedular.execution-timeout-sweep-interval:30s}")
    public void sweep() {
        try {
            Instant now = clock.instant();
            // 粗过滤 cutoff（now-1s）：started_at 为 UTC ISO 字符串，字典序比较成立
            String cutoff = OffsetDateTime.ofInstant(now.minusSeconds(1), ZoneOffset.UTC)
                    .toString();
            for (Execution execution : executionRepository.findTimeoutRunning(cutoff)) {
                timeout(execution, now);
            }
        } catch (RuntimeException e) {
            log.warn("执行超时扫描失败", e);
        }
    }

    private void timeout(Execution execution, Instant now) {
        try {
            Task task = taskRepository.findById(execution.taskId()).orElse(null);
            if (task == null) {
                log.warn("执行对应任务不存在，跳过超时兜底: execution_id={}", execution.executionId());
                return;
            }
            // 精确判定：started_at + timeout_seconds 已过才算超时
            Instant deadline = OffsetDateTime.parse(execution.startedAt()).toInstant()
                    .plusSeconds(task.timeoutSeconds());
            if (!deadline.isBefore(now)) {
                return;
            }
            String nowIso = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).toString();
            // WHERE status='RUNNING'：回调先到则本更新影响 0 行，放弃终局
            if (executionRepository.finalizeTimeoutIfRunning(execution.executionId(),
                    TIMEOUT_REASON, nowIso) == 1) {
                log.warn("执行超时兜底: execution_id={}, task_id={}",
                        execution.executionId(), execution.taskId());
                executionService.handleSweeperTimeout(execution, task, TIMEOUT_REASON);
            }
        } catch (RuntimeException e) {
            log.warn("执行超时兜底失败: execution_id={}", execution.executionId(), e);
        }
    }
}
