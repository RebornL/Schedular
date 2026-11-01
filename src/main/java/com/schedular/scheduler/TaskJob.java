package com.schedular.scheduler;

import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import com.schedular.taskmanager.ExecutionService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 到点真实入口：加载任务 → ExecutionService 建行并触发。
 * 本票不做 misfire / DispatchGuard / 链路重试（#10）；任何异常只记日志，不让 Quartz 任务崩溃。
 */
public class TaskJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionService executionService;

    @Override
    public void execute(JobExecutionContext context) {
        String taskId = context.getMergedJobDataMap().getString(QuartzConfig.DATA_KEY_TASK_ID);
        if (taskId == null) {
            return;
        }
        try {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !"ACTIVE".equals(task.status())) {
                log.info("任务非 ACTIVE，跳过触发: task_id={}", taskId);
                return;
            }
            OffsetDateTime fireTime = OffsetDateTime.ofInstant(
                    context.getScheduledFireTime().toInstant(), ZoneId.of(task.timezone()));
            log.info("任务到点触发: task_id={}, scheduled_fire_time={}", taskId, fireTime);
            executionService.createAndDispatch(task, fireTime);
        } catch (RuntimeException e) {
            log.error("任务触发失败（不崩溃，等待重试语义 #10）: task_id={}", taskId, e);
        }
    }
}
