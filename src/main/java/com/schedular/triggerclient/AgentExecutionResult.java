package com.schedular.triggerclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** 协议三：AgentExecutionResult（SYNC 响应体）；status 仅 SUCCEEDED/FAILED。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentExecutionResult(
        String executionId,
        String taskId,
        String status,
        String startedAt,
        String finishedAt,
        String summary,
        List<BusinessEvent> businessEvents,
        JsonNode report) {

    public boolean statusValid() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BusinessEvent(
            String type,
            String severity,
            String title,
            JsonNode content,
            String dedupKey) {
    }
}
