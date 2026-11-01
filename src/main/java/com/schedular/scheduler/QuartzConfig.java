package com.schedular.scheduler;

import com.schedular.storage.Task;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz 装配：jobStore 类型由 profile 决定（local=memory / prod=jdbc，见 application-*.yml），
 * 此处集中提供 jobKey 约定（jobKey = task_id）与触发器工厂。
 */
@Configuration
public class QuartzConfig {

    public static final String DATA_KEY_TASK_ID = "task_id";

    public static JobDetail buildJobDetail(String taskId) {
        return JobBuilder.newJob(TaskJob.class)
                .withIdentity(taskId)
                .usingJobData(DATA_KEY_TASK_ID, taskId)
                .storeDurably()
                .build();
    }

    public static CronTrigger buildTrigger(Task task) {
        TriggerBuilder<CronTrigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(task.taskId())
                .forJob(task.taskId())
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(task.cronExpression())
                        .withMisfireHandlingInstructionDoNothing()
                        .inTimeZone(TimeZone.getTimeZone(task.timezone())));
        if (task.startAt() != null) {
            builder.startAt(Date.from(OffsetDateTime.parse(task.startAt()).toInstant()));
        } else {
            builder.startNow();
        }
        if (task.endAt() != null) {
            builder.endAt(Date.from(OffsetDateTime.parse(task.endAt()).toInstant()));
        }
        return builder.build();
    }
}
