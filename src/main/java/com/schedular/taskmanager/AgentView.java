package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Agent 响应模型：对外 JSON snake_case；刻意不含 authHeaders（只写不读，docs/design/05-security.md）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentView(
        String agentId,
        String agentVersion,
        String triggerType,
        String endpointUrl,
        String resultMode,
        boolean enabled,
        String createdAt,
        String updatedAt) {

    public static AgentView from(com.schedular.storage.Agent agent) {
        return new AgentView(
                agent.agentId(), agent.agentVersion(), agent.triggerType(),
                agent.endpointUrl(), agent.resultMode(), agent.enabled(),
                agent.createdAt(), agent.updatedAt());
    }
}
