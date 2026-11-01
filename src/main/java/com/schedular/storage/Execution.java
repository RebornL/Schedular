package com.schedular.storage;

/**
 * executions 表领域 record；result 为 JSON 字符串（TEXT 列存 AgentExecutionResult）。
 */
public record Execution(
        String executionId,
        String taskId,
        int configVersion,
        String triggerType,
        String scheduledFireTime,
        int attempt,
        String status,
        String result,
        String summary,
        String lastError,
        String callbackToken,
        String startedAt,
        String finishedAt,
        String createdAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
}
