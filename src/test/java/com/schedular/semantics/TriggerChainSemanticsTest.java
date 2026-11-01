package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #5 缝二：SYNC 全链路 + EVENT_CALLBACK 受理回执（回调接收端是 #9）。
 * stub Agent 经 API 注册（两缝互喂），断言走执行查询接口；events 表目前无查询接口（#7/#11 补），允许 jdbc 直查断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.quartz.auto-startup=true")
@Import(TriggerChainSemanticsTest.StubAgentController.class)
class TriggerChainSemanticsTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String OTHER_CALLER_KEY = "schedular-dev-caller-2";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @RestController
    static class StubAgentController {

        @PostMapping("/stub/agent/{scenario}")
        ResponseEntity<String> handle(@PathVariable String scenario, @RequestBody String body)
                throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String execId = mapper.readTree(body).path("execution_id").asText();
            return switch (scenario) {
                case "sync-ok" -> {
                    Map<String, Object> result = Map.of(
                            "execution_id", execId,
                            "status", "SUCCEEDED",
                            "summary", "巡检完成，一切正常",
                            "business_events", List.of(
                                    Map.of("type", "INVENTORY_RISK", "severity", "HIGH",
                                            "title", "M001 缺料风险", "content", Map.of(),
                                            "dedup_key", "M001-LOW_STOCK"),
                                    Map.of("type", "NO_SEVERITY", "title", "缺 severity 的事件")),
                            "report", Map.of());
                    yield ResponseEntity.ok(toJson(result));
                }
                case "sync-fail" -> ResponseEntity.ok(toJson(Map.of(
                        "execution_id", execId,
                        "status", "FAILED",
                        "summary", "Agent 内部业务失败")));
                case "sync-timeout" -> {
                    Thread.sleep(8000);
                    yield ResponseEntity.ok(toJson(Map.of(
                            "execution_id", execId,
                            "status", "SUCCEEDED",
                            "summary", "来得太晚")));
                }
                case "ack-ok" -> ResponseEntity.ok(toJson(Map.of(
                        "accepted", true, "execution_id", execId)));
                case "ack-invalid" -> ResponseEntity.ok(toJson(Map.of(
                        "accepted", false)));
                default -> ResponseEntity.status(500)
                        .body("{\"code\":\"AGENT_ERROR\",\"message\":\"stub 故障\"}");
            };
        }

        private static String toJson(Map<String, Object> body) {
            try {
                return new ObjectMapper().writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void seedOtherCallerKey() {
        // 第二个 CALLER key（tenant-other），仅测试租户隔离用
        jdbc.update("""
                INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at)
                VALUES (?, 'other-caller', 'CALLER', 'tenant-other', 1, '2026-01-01T00:00:00+08:00')
                ON CONFLICT(key_hash) DO NOTHING""",
                java.util.HexFormat.of().formatHex(sha256(OTHER_CALLER_KEY)));
    }

    private static byte[] sha256(String input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", apiKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> exchange(String method, String path, String apiKey, Object body) {
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers(apiKey)), String.class);
    }

    private void registerAgent(String agentId, String scenario, String resultMode) {
        ResponseEntity<String> resp = exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", agentId,
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "http://localhost:" + port + "/stub/agent/" + scenario,
                "resultMode", resultMode));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private String createTask(String agentId, int timeoutSeconds) {
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "链路测试-" + agentId,
                        "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", timeoutSeconds, "retry_count", 2,
                        "max_concurrent_executions", 1)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return field(resp.getBody(), "task_id");
    }

    private List<JsonNode> executions(String taskId, String apiKey) {
        ResponseEntity<String> resp = exchange("GET", "/api/tasks/" + taskId + "/executions",
                apiKey, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode node = objectMapper.readTree(resp.getBody());
            List<JsonNode> list = new CopyOnWriteArrayList<>();
            node.forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awaitExecution(String taskId, String expectedStatus) {
        try {
            return await().atMost(Duration.ofSeconds(20)).until(() -> {
                List<JsonNode> list = executions(taskId, CALLER_KEY);
                return list.stream()
                        .filter(e -> expectedStatus.equals(e.path("status").asText()))
                        .findFirst().orElse(null);
            }, e -> e != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("等待状态 " + expectedStatus + " 超时，当前: "
                    + executions(taskId, CALLER_KEY), e);
        }
    }

    private static String field(String body, String name) {
        try {
            return new ObjectMapper().readTree(body).path(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void sync成功链路落SUCCEEDED并转业务事件() throws Exception {
        registerAgent("agent_t5_sync_ok", "sync-ok", "SYNC");
        String taskId = createTask("agent_t5_sync_ok", 10);

        JsonNode exec = awaitExecution(taskId, "SUCCEEDED");
        assertThat(exec.path("attempt").asInt()).isEqualTo(1);
        assertThat(exec.path("config_version").asInt()).isEqualTo(1);
        assertThat(exec.path("started_at").asText()).isNotBlank();
        assertThat(exec.path("finished_at").asText()).isNotBlank();
        assertThat(exec.path("result").path("summary").asText()).isEqualTo("巡检完成，一切正常");
        assertThat(exec.path("summary").asText()).isEqualTo("巡检完成，一切正常");
        assertThat(exec.path("trigger_type").asText()).isEqualTo("SCHEDULED");
        assertThat(exec.path("scheduled_fire_time").asText()).isNotBlank();
        assertThat(exec.path("callback_token").isMissingNode()).isTrue();

        String executionId = exec.path("execution_id").asText();
        // 受理→终态事件 + 业务事件落 events；缺 severity 的业务事件被丢弃
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> events = jdbc.queryForList(
                    "SELECT event_type, severity, execution_id, payload FROM events "
                            + "WHERE task_id = ?", taskId);
            assertThat(events).extracting(e -> e.get("event_type"))
                    .contains("EXECUTION_SUCCEEDED", "BUSINESS_EVENT");
            assertThat(events.stream().filter(e -> "BUSINESS_EVENT".equals(e.get("event_type"))))
                    .hasSize(1)
                    .allMatch(e -> "HIGH".equals(e.get("severity"))
                            && executionId.equals(e.get("execution_id"))
                            && String.valueOf(e.get("payload")).contains("M001-LOW_STOCK"));
            assertThat(events.stream().filter(e -> "EXECUTION_SUCCEEDED".equals(e.get("event_type"))))
                    .allMatch(e -> executionId.equals(e.get("execution_id"))
                            && String.valueOf(e.get("payload")).contains("\"attempt\":1"));
        });
    }

    @Test
    void sync业务FAILED不重试attempt不增并产生EXECUTION_FAILED事件() throws Exception {
        registerAgent("agent_t5_sync_fail", "sync-fail", "SYNC");
        String taskId = createTask("agent_t5_sync_fail", 10);

        JsonNode exec = awaitExecution(taskId, "FAILED");
        assertThat(exec.path("attempt").asInt()).isEqualTo(1);
        assertThat(exec.path("last_error").isMissingNode()
                || exec.path("last_error").isNull()).isTrue();
        String executionId = exec.path("execution_id").asText();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM events WHERE task_id = ? AND event_type = "
                                + "'EXECUTION_FAILED' AND execution_id = ?",
                        Integer.class, taskId, executionId)).isEqualTo(1));
    }

    @Test
    void sync执行超时落TIMEOUT终态() throws Exception {
        registerAgent("agent_t5_sync_timeout", "sync-timeout", "SYNC");
        String taskId = createTask("agent_t5_sync_timeout", 1);

        JsonNode exec = awaitExecution(taskId, "TIMEOUT");
        assertThat(exec.path("result").isNull()
                || exec.path("result").isMissingNode()).isTrue();
        String executionId = exec.path("execution_id").asText();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM events WHERE task_id = ? AND event_type = "
                                + "'EXECUTION_TIMEOUT' AND execution_id = ?",
                        Integer.class, taskId, executionId)).isEqualTo(1));
    }

    @Test
    void agent5xx按链路故障处理状态保持PENDING不崩溃() throws Exception {
        registerAgent("agent_t5_http500", "http500", "SYNC");
        String taskId = createTask("agent_t5_http500", 10);

        JsonNode exec = awaitExecution(taskId, "PENDING");
        assertThat(exec.path("last_error").asText()).contains("HTTP 500");
        assertThat(exec.path("attempt").asInt()).isEqualTo(1);
        // 应用未崩溃：查询接口仍然正常
        assertThat(exchange("GET", "/api/tasks/" + taskId, CALLER_KEY, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void event_callback回执有效时execution进入RUNNING并存callback_token() throws Exception {
        registerAgent("agent_t5_ack_ok", "ack-ok", "EVENT_CALLBACK");
        String taskId = createTask("agent_t5_ack_ok", 10);

        JsonNode exec = awaitExecution(taskId, "RUNNING");
        String executionId = exec.path("execution_id").asText();
        assertThat(exec.path("result").isNull() || exec.path("result").isMissingNode()).isTrue();
        String token = jdbc.queryForObject(
                "SELECT callback_token FROM executions WHERE execution_id = ?",
                String.class, executionId);
        assertThat(token).isNotBlank();
    }

    @Test
    void event_callback回执无效按链路故障处理() throws Exception {
        registerAgent("agent_t5_ack_bad", "ack-invalid", "EVENT_CALLBACK");
        String taskId = createTask("agent_t5_ack_bad", 10);

        JsonNode exec = awaitExecution(taskId, "PENDING");
        assertThat(exec.path("last_error").asText()).contains("受理回执无效");
    }

    @Test
    void 执行查询含结果与版本引用且跨租户404() throws Exception {
        registerAgent("agent_t5_query", "sync-ok", "SYNC");
        String taskId = createTask("agent_t5_query", 10);
        JsonNode exec = awaitExecution(taskId, "SUCCEEDED");
        String executionId = exec.path("execution_id").asText();

        // 单条查询：含结果与配置版本引用
        ResponseEntity<String> one = exchange("GET", "/api/executions/" + executionId,
                CALLER_KEY, null);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = objectMapper.readTree(one.getBody());
        assertThat(node.path("execution_id").asText()).isEqualTo(executionId);
        assertThat(node.path("task_id").asText()).isEqualTo(taskId);
        assertThat(node.path("config_version").asInt()).isEqualTo(1);
        assertThat(node.path("result").path("status").asText()).isEqualTo("SUCCEEDED");

        // 跨租户：列表与单条均 404
        assertThat(exchange("GET", "/api/tasks/" + taskId + "/executions",
                OTHER_CALLER_KEY, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("GET", "/api/executions/" + executionId,
                OTHER_CALLER_KEY, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("GET", "/api/executions/E" + "nonexistent000000000000000000000",
                CALLER_KEY, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
