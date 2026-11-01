package com.schedular.storage;

/**
 * events 表领域 record；payload 为 JSON 字符串（TEXT 列存 JSON）。
 */
public record Event(
        String eventId,
        String eventType,
        String tenantId,
        String taskId,
        String executionId,
        String severity,
        String occurredAt,
        String payload) {
}
