package com.schedular.triggerclient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 协议二：调度服务 → Agent 的触发请求体（snake_case）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentTriggerRequest(
        String taskId,
        String executionId,
        int taskConfigVersion,
        String triggerType,
        String triggerTime,
        Callback callback) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Callback(
            String resultMode,
            String callbackEndpoint,
            String callbackToken) {
    }
}
