package com.schedular.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 连续失败升级配置：schedular.escalation.threshold，默认 3。 */
@Component
public class EscalationProperties {

    private final int threshold;

    public EscalationProperties(@Value("${schedular.escalation.threshold:3}") int threshold) {
        this.threshold = threshold;
    }

    public int threshold() {
        return threshold;
    }
}
