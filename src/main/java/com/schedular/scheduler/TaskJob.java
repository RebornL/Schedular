package com.schedular.scheduler;

import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import com.schedular.taskmanager.ExecutionService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 到点真实入口：misfire 判定 → DispatchGuard 并发判定 → ExecutionService 建行并触发。
 * 任何异常只记日志，不让 Quartz 任务崩溃。
 */
public class TaskJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private DispatchGuard dispatchGuard;

    @Autowired
    private com.schedular.events.EventService eventService;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private MisfireProperties misfireProperties;

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
            // MISFIRE：过期超过阈值则不补跑（04），Quartz DO_NOTHING 之外的双保险
            long lateMs = Duration.between(fireTime.toInstant(), clock.instant()).toMillis();
            if (lateMs > misfireProperties.thresholdSeconds() * 1000) {
                log.warn("MISFIRE，跳过不补跑: task_id={}, scheduled_fire_time={}, 迟到={}ms",
                        taskId, fireTime, lateMs);
                eventService.persist("MISFIRE", task.tenantId(), task.taskId(),
                        null, null, Map.of("scheduled_fire_time", fireTime.toString()));
                return;
            }
            log.info("任务到点触发: task_id={}, scheduled_fire_time={}", taskId, fireTime);
            if (!dispatchGuard.tryAcquire(task, null)) {
                return;
            }
            executionService.createAndDispatch(task, fireTime);
        } catch (RuntimeException e) {
            log.error("任务触发失败: task_id={}", taskId, e);
        }
    }
}
