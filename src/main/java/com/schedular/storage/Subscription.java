package com.schedular.storage;

/**
 * subscriptions 表领域 record；filter_task_ids / filter_event_types 为 JSON 数组字符串，可空。
 */
public record Subscription(
        Long id,
        String tenantId,
        String callbackUrl,
        String secret,
        String filterTaskIds,
        String filterEventTypes,
        String minSeverity,
        Integer dedupWindowSeconds,
        boolean enabled,
        String createdAt) {
}
