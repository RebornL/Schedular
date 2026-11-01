package com.schedular.scheduler;

import com.schedular.common.ApiException;
import com.schedular.storage.Task;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronExpression;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Quartz 生命周期封装：register / pause / resume / remove，jobKey = task_id。 */
@Component
public class TaskScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduleManager.class);

    private final Scheduler scheduler;
    private final Clock clock;

    public TaskScheduleManager(Scheduler scheduler, Clock clock) {
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public void register(Task task) {
        try {
            JobDetail job = QuartzConfig.buildJobDetail(task.taskId());
            Trigger trigger = QuartzConfig.buildTrigger(task);
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "Quartz 注册失败: " + e.getMessage());
        }
    }

    /** 触发器替换：jobKey/triggerKey 不变，cron/timezone/start_at/end_at 变更生效。 */
    public void reschedule(Task task) {
        try {
            org.quartz.JobKey jobKey = org.quartz.JobKey.jobKey(task.taskId());
            Trigger trigger = QuartzConfig.buildTrigger(task);
            org.quartz.TriggerKey triggerKey = trigger.getKey();
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else if (scheduler.checkExists(jobKey)) {
                scheduler.scheduleJob(trigger);
            } else {
                scheduler.scheduleJob(QuartzConfig.buildJobDetail(task.taskId()), trigger);
            }
        } catch (SchedulerException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "Quartz 重排触发器失败: " + e.getMessage());
        }
    }

    public void pause(String taskId) {
        try {
            scheduler.pauseJob(org.quartz.JobKey.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "Quartz 暂停失败: " + e.getMessage());
        }
    }

    public void resume(String taskId) {
        try {
            scheduler.resumeJob(org.quartz.JobKey.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "Quartz 恢复失败: " + e.getMessage());
        }
    }

    public void remove(String taskId) {
        try {
            scheduler.deleteJob(org.quartz.JobKey.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "Quartz 移除失败: " + e.getMessage());
        }
    }

    /**
     * 按当前 cron 计算下一次触发时间（任务时区）；PAUSED/STOPPED 或超出 end_at 返回 null。
     */
    public OffsetDateTime nextExecutionTime(Task task) {
        if (!"ACTIVE".equals(task.status())) {
            return null;
        }
        CronExpression cron;
        try {
            cron = new CronExpression(task.cronExpression());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_ERROR",
                    "cron 表达式无法解析: " + task.cronExpression());
        }
        cron.setTimeZone(TimeZone.getTimeZone(task.timezone()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (task.startAt() != null) {
            OffsetDateTime startAt = OffsetDateTime.parse(task.startAt());
            if (startAt.isAfter(now)) {
                now = startAt;
            }
        }
        Date next = cron.getNextValidTimeAfter(Date.from(now.toInstant()));
        if (next == null) {
            return null;
        }
        OffsetDateTime nextTime = OffsetDateTime.ofInstant(next.toInstant(), ZoneId.of(task.timezone()));
        if (task.endAt() != null && nextTime.isAfter(OffsetDateTime.parse(task.endAt()))) {
            return null;
        }
        return nextTime;
    }
}
