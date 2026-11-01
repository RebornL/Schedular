package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;

/** POST /api/subscriptions 请求体（03 协议；dedup_window_seconds 为 #8 范围，先收下）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionRequest(
        String callbackUrl,
        List<String> filterTaskIds,
        List<String> filterEventTypes,
        String minSeverity,
        Integer dedupWindowSeconds) {
}
