package com.schedular.triggerclient;

import com.schedular.common.ApiException;
import com.schedular.storage.Execution;
import com.schedular.storage.ExecutionRepository;
import com.schedular.storage.Task;
import com.schedular.storage.TaskRepository;
import com.schedular.taskmanager.ExecutionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协议三 EVENT_CALLBACK 接收端：POST /api/agent-executions/{execution_id}/result。
 * X-Callback-Token 即凭证（路径豁免 X-API-Key 过滤器）；首次有效回调生效，重复回调幂等 200。
 */
@RestController
public class CallbackController {

    private final ExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final ExecutionService executionService;

    public CallbackController(ExecutionRepository executionRepository,
                              TaskRepository taskRepository,
                              ExecutionService executionService) {
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.executionService = executionService;
    }

    @PostMapping("/api/agent-executions/{executionId}/result")
    public ResponseEntity<Map<String, Boolean>> result(
            @PathVariable String executionId,
            @RequestHeader(value = "X-Callback-Token", required = false) String token,
            @RequestBody AgentExecutionResult result) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND",
                        "执行不存在: " + executionId));
        if (token == null || token.isBlank() || execution.callbackToken() == null
                || !MessageDigest.isEqual(
                        execution.callbackToken().getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_CALLBACK_TOKEN",
                    "回调 token 缺失或错误");
        }
        if (result.executionId() != null && !result.executionId().equals(executionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXECUTION_ID_MISMATCH",
                    "路径 execution_id 与结果体 execution_id 不一致");
        }
        if (!result.statusValid()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESULT_STATUS",
                    "非法执行状态: " + result.status());
        }
        switch (execution.status()) {
            case Execution.STATUS_SUCCEEDED, Execution.STATUS_FAILED -> {
                // 重复有效回调：首次已生效，幂等返回、不产生新事件
            }
            case Execution.STATUS_TIMEOUT -> throw new ApiException(HttpStatus.CONFLICT,
                    "EXECUTION_ALREADY_TERMINATED", "执行已终态（TIMEOUT），回调被拒绝");
            case Execution.STATUS_PENDING -> throw new ApiException(HttpStatus.CONFLICT,
                    "EXECUTION_NOT_RUNNING", "执行尚未进入 RUNNING，回调被拒绝");
            default -> {
                Task task = taskRepository.findById(execution.taskId())
                        .orElseThrow(() -> new IllegalStateException(
                                "执行对应任务不存在: " + execution.taskId()));
                executionService.handleAgentResult(execution, task, result);
            }
        }
        return ResponseEntity.ok(Map.of("received", true));
    }
}
