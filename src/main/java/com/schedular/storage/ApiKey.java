package com.schedular.storage;

/** api_keys 表领域 record。 */
public record ApiKey(int id, String keyHash, String callerName, String role, String tenantId, boolean enabled) {
}
