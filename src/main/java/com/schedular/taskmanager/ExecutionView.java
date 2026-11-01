package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.schedular.storage.Execution;

/** 执行记录视图（不含 callback_token——一次性凭证不外泄）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExecutionView(
        String executionId,
        String taskId,
        int configVersion,
        String triggerType,
        String scheduledFireTime,
        int attempt,
        String status,
        JsonNode result,
        String summary,
        String lastError,
        String startedAt,
        String finishedAt,
        String createdAt) {

    public static ExecutionView from(Execution e, ObjectMapper objectMapper) {
        JsonNode result = null;
        if (e.result() != null) {
            try {
                result = objectMapper.readTree(e.result());
            } catch (Exception ignored) {
                // result 列始终由服务端序列化写入；异常时保持 null
            }
        }
        return new ExecutionView(e.executionId(), e.taskId(), e.configVersion(), e.triggerType(),
                e.scheduledFireTime(), e.attempt(), e.status(), result, e.summary(),
                e.lastError(), e.startedAt(), e.finishedAt(), e.createdAt());
    }
}
