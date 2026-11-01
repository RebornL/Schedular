package com.schedular.scheduler;

import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import com.schedular.taskmanager.ExecutionService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 链路重试一次性 Job：同一 execution_id，attempt+1，任务定义取当前生效版本，
 * 重新走 dispatch 全流程（含 DispatchGuard 并发判定跳过）。业务 FAILED/TIMEOUT 不会走到这里。
 */
public class RetryJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(RetryJob.class);

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private DispatchGuard dispatchGuard;

    @Autowired
    private ExecutionService executionService;

    @Override
    public void execute(JobExecutionContext context) {
        String taskId = context.getMergedJobDataMap().getString(RetryScheduler.DATA_KEY_TASK_ID);
        String executionId = context.getMergedJobDataMap()
                .getString(RetryScheduler.DATA_KEY_EXECUTION_ID);
        try {
            Task task = taskRepository.findById(taskId).orElse(null);
            Execution execution = executionRepository.findById(executionId).orElse(null);
            if (task == null || execution == null) {
                log.warn("重试中止，记录缺失: task_id={}, execution_id={}", taskId, executionId);
                return;
            }
            if (Execution.STATUS_SUCCEEDED.equals(execution.status())
                    || Execution.STATUS_FAILED.equals(execution.status())
                    || Execution.STATUS_TIMEOUT.equals(execution.status())) {
                log.warn("重试中止，已终态: execution_id={}, status={}",
                        executionId, execution.status());
                return;
            }
            if (!"ACTIVE".equals(task.status())) {
                executionService.failExhausted(execution, task, "重试中止: 任务非 ACTIVE: " + taskId);
                return;
            }
            if (!dispatchGuard.tryAcquire(task, executionId)) {
                // SKIPPED_CONCURRENT 已记，保持 PENDING（04：重试触发也受并发约束）
                return;
            }
            int nextAttempt = execution.attempt() + 1;
            executionRepository.updateAttempt(executionId, nextAttempt);
            log.info("链路重试触发: task_id={}, execution_id={}, attempt={}",
                    taskId, executionId, nextAttempt);
            executionService.dispatch(withAttempt(execution, nextAttempt), task);
        } catch (RuntimeException e) {
            log.error("链路重试失败（不崩溃）: task_id={}, execution_id={}", taskId, executionId, e);
        }
    }

    private static Execution withAttempt(Execution e, int attempt) {
        return new Execution(e.executionId(), e.taskId(), e.configVersion(), e.triggerType(),
                e.scheduledFireTime(), attempt, e.status(), e.result(), e.summary(),
                e.lastError(), e.callbackToken(), e.startedAt(), e.finishedAt(), e.createdAt());
    }
}
