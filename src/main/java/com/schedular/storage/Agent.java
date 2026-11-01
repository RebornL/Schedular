package com.schedular.storage;

/** agents 表领域 record；authHeaders 为 JSON 字符串（只写不读，任何响应不回显）。 */
public record Agent(
        String agentId,
        String agentVersion,
        String triggerType,
        String endpointUrl,
        String resultMode,
        String authHeaders,
        boolean enabled,
        String createdAt,
        String updatedAt) {
}
