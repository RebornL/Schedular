package com.schedular.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 推送重试调度池（Spring TaskScheduler）；与 Quartz 调度池独立。 */
@Configuration
public class PushSchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler pushTaskScheduler(
            @Value("${schedular.dispatch.threads:8}") int threads) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threads);
        scheduler.setThreadNamePrefix("push-");
        scheduler.initialize();
        return scheduler;
    }
}
