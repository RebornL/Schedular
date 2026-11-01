package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.common.ApiException;
import com.schedular.security.TenantContext;
import com.schedular.security.TenantPrincipal;
import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 执行记录只读查询：租户隔离，跨租户一律 404。 */
@RestController
public class ExecutionQueryController {

    private final ExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public ExecutionQueryController(ExecutionRepository executionRepository,
                                    TaskRepository taskRepository,
                                    ObjectMapper objectMapper) {
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/tasks/{taskId}/executions")
    public List<ExecutionView> byTask(@PathVariable String taskId) {
        requireOwnedTask(taskId);
        return executionRepository.findByTaskId(taskId).stream()
                .map(e -> ExecutionView.from(e, objectMapper))
                .toList();
    }

    @GetMapping("/api/executions/{executionId}")
    public ExecutionView get(@PathVariable String executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> notFound("EXECUTION_NOT_FOUND", "执行记录不存在: " + executionId));
        requireOwnedTask(execution.taskId());
        return ExecutionView.from(execution, objectMapper);
    }

    private void requireOwnedTask(String taskId) {
        TenantPrincipal principal = TenantContext.get();
        Task task = taskRepository.findById(taskId)
                .filter(t -> principal == null || principal.tenantId() == null
                        || principal.tenantId().equals(t.tenantId()))
                .orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
