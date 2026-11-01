package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** 协议一：ScheduledTaskCreateRequest 六块结构（无 notification 块），对外 JSON snake_case。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskCreateRequest(
        TaskBlock task,
        TargetBlock target,
        ScheduleBlock schedule,
        ExecutionBlock execution,
        SecurityBlock security,
        ExecutionPolicyBlock executionPolicy) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskBlock(
            String name,
            String description,
            String taskType) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetBlock(
            String agentId,
            String agentVersion,
            String triggerType) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScheduleBlock(
            String type,
            String expression,
            String timezone,
            String startAt,
            String endAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionBlock(
            String instruction,
            JsonNode parameters,
            List<String> contextRefs) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SecurityBlock(
            String tenantId,
            String ownerUserId,
            String permissionContextRef) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionPolicyBlock(
            Integer timeoutSeconds,
            Integer retryCount,
            Integer maxConcurrentExecutions) {
    }
}
