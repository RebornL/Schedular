package com.schedular.storage;

/**
 * tasks 表领域 record；parameters/contextRefs 为 JSON 字符串（TEXT 列存 JSON）。
 */
public record Task(
        String taskId,
        String tenantId,
        String ownerUserId,
        String permissionContextRef,
        String name,
        String description,
        String taskType,
        String agentId,
        String agentVersion,
        String triggerType,
        String scheduleType,
        String cronExpression,
        String timezone,
        String startAt,
        String endAt,
        String instruction,
        String parameters,
        String contextRefs,
        int timeoutSeconds,
        int retryCount,
        int maxConcurrentExecutions,
        int currentVersion,
        String status,
        int consecutiveFailures,
        String createdAt,
        String updatedAt) {
}
