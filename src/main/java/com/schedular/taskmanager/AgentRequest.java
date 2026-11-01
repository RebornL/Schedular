package com.schedular.taskmanager;

import java.util.Map;

/** Agent 注册请求体。authHeaders 只写不读。 */
public record AgentRequest(
        String agentId,
        String agentVersion,
        String triggerType,
        String endpointUrl,
        String resultMode,
        Map<String, String> authHeaders) {
}
