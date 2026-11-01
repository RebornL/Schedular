package com.schedular.scheduler;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 占位到点入口：本票只记录触发（Execution 建行与 Agent 触发链路属于下一张票）。
 * misfire / 并发判定由 DispatchGuard 在触发链路接入时实现。
 */
public class TaskJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        String taskId = context.getMergedJobDataMap().getString(QuartzConfig.DATA_KEY_TASK_ID);
        log.info("任务到点触发: task_id={}, scheduled_fire_time={}",
                taskId, context.getScheduledFireTime());
    }
}
