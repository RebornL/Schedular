package com.schedular.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** MISFIRE 判定配置：schedular.misfire-threshold-seconds，默认 60s。 */
@Component
public class MisfireProperties {

    private final long thresholdSeconds;

    public MisfireProperties(@Value("${schedular.misfire-threshold-seconds:60}") long thresholdSeconds) {
        this.thresholdSeconds = thresholdSeconds;
    }

    public long thresholdSeconds() {
        return thresholdSeconds;
    }
}
