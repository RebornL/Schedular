package com.schedular.triggerclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.storage.Agent;
import com.schedular.storage.Execution;
import com.schedular.storage.Task;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 对 Agent 的出站触发调用（java.net.http.HttpClient）。
 * SYNC：总超时 = timeout_seconds，超时 → TIMEOUT 终态；
 * EVENT_CALLBACK：ack 窗口 = schedular.ack-timeout-seconds，超时/无有效回执 → 链路故障。
 */
@Component
public class AgentTriggerClient {

    private static final Logger log = LoggerFactory.getLogger(AgentTriggerClient.class);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final int ackTimeoutSeconds;

    public AgentTriggerClient(ObjectMapper objectMapper,
                              @Value("${schedular.ack-timeout-seconds:30}") int ackTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    public TriggerOutcome trigger(Task task, Agent agent, Execution execution) {
        boolean sync = "SYNC".equals(agent.resultMode());
        AgentTriggerRequest request = new AgentTriggerRequest(
                task.taskId(), execution.executionId(), task.currentVersion(), "SCHEDULED",
                OffsetDateTime.now().toString(),
                sync ? null : new AgentTriggerRequest.Callback(
                        agent.resultMode(),
                        "/api/agent-executions/" + execution.executionId() + "/result",
                        execution.callbackToken()));
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (IOException e) {
            return TriggerOutcome.linkFailure("触发请求序列化失败: " + e.getMessage());
        }
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(agent.endpointUrl()))
                    .timeout(Duration.ofSeconds(sync ? task.timeoutSeconds() : ackTimeoutSeconds))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (Exception e) {
            return TriggerOutcome.linkFailure("触发请求构建失败: " + e.getMessage());
        }
        httpRequest = withAuthHeaders(httpRequest, agent.authHeaders());
        try {
            log.info("触发 Agent: task_id={}, execution_id={}, endpoint={}, mode={}",
                    task.taskId(), execution.executionId(), agent.endpointUrl(), agent.resultMode());
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return TriggerOutcome.linkFailure(
                        "Agent 返回非 2xx: HTTP " + response.statusCode());
            }
            return parseResponse(response.body(), execution, sync);
        } catch (java.net.http.HttpTimeoutException e) {
            return sync
                    ? TriggerOutcome.timeout("执行超时: timeout_seconds=" + task.timeoutSeconds())
                    : TriggerOutcome.linkFailure("受理回执等待超时: ack 窗口 " + ackTimeoutSeconds + "s");
        } catch (IOException e) {
            return TriggerOutcome.linkFailure("Agent 连接失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TriggerOutcome.linkFailure("触发调用被中断");
        }
    }

    private TriggerOutcome parseResponse(String body, Execution execution, boolean sync) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return TriggerOutcome.linkFailure("响应体不是合法 JSON: " + e.getMessage());
        }
        if (sync) {
            if (!root.has("status")) {
                return TriggerOutcome.linkFailure("2xx 响应缺少 status 字段，非 AgentExecutionResult");
            }
            AgentExecutionResult result;
            try {
                result = objectMapper.treeToValue(root, AgentExecutionResult.class);
            } catch (IOException e) {
                return TriggerOutcome.linkFailure("AgentExecutionResult 解析失败: " + e.getMessage());
            }
            if (!result.statusValid()) {
                return TriggerOutcome.linkFailure("非法执行状态: " + result.status());
            }
            if (result.executionId() != null && !result.executionId().equals(execution.executionId())) {
                return TriggerOutcome.linkFailure("结果 execution_id 不匹配: " + result.executionId());
            }
            return TriggerOutcome.result(result);
        }
        boolean accepted = root.path("accepted").asBoolean(false);
        String echoedId = root.path("execution_id").asText(null);
        if (accepted && execution.executionId().equals(echoedId)) {
            return TriggerOutcome.ack();
        }
        return TriggerOutcome.linkFailure("受理回执无效: accepted=" + accepted + ", execution_id=" + echoedId);
    }

    /** registry.auth_headers（JSON 对象，只写不读——仅在出站调用时注入，不回显）。 */
    private HttpRequest withAuthHeaders(HttpRequest request, String authHeadersJson) {
        if (authHeadersJson == null || authHeadersJson.isBlank()) {
            return request;
        }
        try {
            Map<String, String> headers = new HashMap<>();
            objectMapper.readTree(authHeadersJson).properties()
                    .forEach(e -> headers.put(e.getKey(), String.valueOf(e.getValue())));
            return HttpRequest.newBuilder(request.uri())
                    .method(request.method(), request.bodyPublisher().orElse(null))
                    .headers(buildHeaderPairs(headers))
                    .build();
        } catch (IOException e) {
            log.warn("auth_headers 解析失败，按无凭证头调用: {}", e.getMessage());
            return request;
        }
    }

    private String[] buildHeaderPairs(Map<String, String> headers) {
        return headers.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }
}
