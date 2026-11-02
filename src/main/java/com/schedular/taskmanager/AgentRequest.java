package com.schedular.taskmanager;

import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Agent 注册请求体。对外 JSON 一律 snake_case（docs/design/03-protocols.md）。authHeaders 只写不读。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentRequest(
        String agentId,
        String agentVersion,
        String triggerType,
        String endpointUrl,
        String resultMode,
        Map<String, String> authHeaders) {
}
