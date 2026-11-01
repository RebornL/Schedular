package com.schedular.storage;

/** task_config_versions 表领域 record；definition 为完整六块请求 JSON 快照。 */
public record TaskConfigVersion(
        Long id,
        String taskId,
        int version,
        String definition,
        String createdAt) {
}
