package com.schedular.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 时钟统一注入，测试可替换。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        // 持久化时间戳一律 UTC（ISO Z 结尾），保证字符串字典序比较（since/超时/去重）跨方言正确
        return Clock.systemUTC();
    }
}
