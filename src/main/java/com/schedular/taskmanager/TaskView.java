package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 任务详情视图：全字段 + current_version + next_execution_time（snake_case）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskView(
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
        JsonNode parameters,
        JsonNode contextRefs,
        int timeoutSeconds,
        int retryCount,
        int maxConcurrentExecutions,
        int currentVersion,
        String status,
        int consecutiveFailures,
        String createdAt,
        String updatedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        String nextExecutionTime) {

    /** 创建/更新响应（协议一规定字段）：task_id / status / agent_id / next_execution_time / current_version。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Created(
            String taskId,
            String status,
            String agentId,
            String nextExecutionTime,
            int currentVersion) {
    }
}
