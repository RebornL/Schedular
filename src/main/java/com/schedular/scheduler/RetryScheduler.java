package com.schedular.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RetryScheduler：链路故障后安排一次性重试（docs/design/04 链路重试）。
 * Quartz one-shot，30s 后（schedular.retry-delay-seconds 可调），同一 execution_id，attempt+1。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    public static final String DATA_KEY_TASK_ID = "retry_task_id";
    public static final String DATA_KEY_EXECUTION_ID = "retry_execution_id";

    private final Scheduler scheduler;
    private final Clock clock;
    private final long delaySeconds;

    public RetryScheduler(Scheduler scheduler, Clock clock,
                          @Value("${schedular.retry-delay-seconds:30}") long delaySeconds) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.delaySeconds = delaySeconds;
    }

    public void scheduleRetry(String taskId, String executionId, int nextAttempt) {
        try {
            JobDetail job = JobBuilder.newJob(RetryJob.class)
                    .withIdentity("retry-" + executionId + "-" + nextAttempt)
                    .usingJobData(DATA_KEY_TASK_ID, taskId)
                    .usingJobData(DATA_KEY_EXECUTION_ID, executionId)
                    .build();
            org.quartz.Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("retry-trig-" + executionId + "-" + nextAttempt)
                    .forJob(job.getKey())
                    .startAt(Date.from(Instant.now(clock).plusSeconds(delaySeconds)))
                    .build();
            scheduler.scheduleJob(job, trigger);
            log.info("已安排链路重试: task_id={}, execution_id={}, next_attempt={}, delay={}s",
                    taskId, executionId, nextAttempt, delaySeconds);
        } catch (SchedulerException e) {
            throw new IllegalStateException("重试调度失败: execution_id=" + executionId, e);
        }
    }
}
