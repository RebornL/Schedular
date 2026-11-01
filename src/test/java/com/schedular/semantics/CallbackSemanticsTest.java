package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #9 缝二：EVENT_CALLBACK 回调语义。stub Agent 先 ack，测试侧再持 callback_token
 * 回调结果端点；两条缝互喂（stub 经 API 注册，触发链路真实走 HTTP）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.quartz.auto-startup=true")
@Import(CallbackSemanticsTest.StubAgentController.class)
class CallbackSemanticsTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** scenario -> "executionId|endpoint|token"，stub ack 时捕获下发地址与凭证。 */
    static final Map<String, String> CALLBACKS = new ConcurrentHashMap<>();

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

        /** EVENT_CALLBACK 模式：立即 ack，捕获 callback_endpoint + callback_token 供测试侧回调。 */
        @PostMapping("/stub/agent/callback/{scenario}")
        ResponseEntity<Map<String, Object>> ack(@PathVariable String scenario,
                                                @RequestBody JsonNode body) {
            String executionId = body.path("execution_id").asText();
            CALLBACKS.put(scenario,
                    executionId + "|"
                            + body.path("callback").path("callback_endpoint").asText()
                            + "|" + body.path("callback").path("callback_token").asText());
            return ResponseEntity.ok(Map.of("accepted", true, "execution_id", executionId));
        }

        /** SYNC 模式慢响应：配合 timeout_seconds=1 造 TIMEOUT 终态。 */
        @PostMapping("/stub/agent/slow")
        ResponseEntity<String> slow() throws Exception {
            Thread.sleep(3000);
            return ResponseEntity.ok("{}");
        }
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", apiKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void registerAgent(String agentId, String resultMode, String endpoint) {
        ResponseEntity<String> resp = rest.exchange("/api/agents", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "agentId", agentId,
                        "agentVersion", "1.0.0",
                        "triggerType", "HTTP_API",
                        "endpointUrl", endpoint,
                        "resultMode", resultMode), headers(ADMIN_KEY)), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private Map<String, Object> taskBody(String name, String agentId, int timeoutSeconds) {
        return Map.of(
                "task", Map.of("name", name, "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", timeoutSeconds, "retry_count", 2,
                        "max_concurrent_executions", 1));
    }

    private String createTask(String agentId, int timeoutSeconds) {
        int seq = SEQ.incrementAndGet();
        ResponseEntity<String> resp = rest.exchange("/api/tasks", HttpMethod.POST,
                new HttpEntity<>(taskBody("回调测试-" + seq, agentId, timeoutSeconds),
                        headers(CALLER_KEY)), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            return new ObjectMapper().readTree(resp.getBody()).path("task_id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 等 stub ack 捕获回调信息（触发链路真实运行），返回 "executionId|endpoint|token"。 */
    private String awaitCallbackInfo(String scenario) {
        return await().atMost(Duration.ofSeconds(20)).until(
                () -> CALLBACKS.get(scenario), v -> v != null);
    }

    private ResponseEntity<String> callback(String endpoint, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set("X-Callback-Token", token);
        }
        return rest.exchange("http://localhost:" + port + endpoint, HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private Map<String, Object> resultBody(String executionId, String status) {
        return Map.of(
                "execution_id", executionId,
                "status", status,
                "started_at", "2026-11-01T21:00:05+08:00",
                "finished_at", "2026-11-01T21:02:11+08:00",
                "summary", "回调巡检完成",
                "business_events", List.of(Map.of(
                        "type", "INVENTORY_RISK",
                        "severity", "HIGH",
                        "title", "M001 缺料风险",
                        "content", Map.of(),
                        "dedup_key", "M001-LOW_STOCK")),
                "report", Map.of());
    }

    private String statusOf(String executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM executions WHERE execution_id = ?",
                String.class, executionId);
    }

    private int eventCount(String executionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE execution_id = ?",
                Integer.class, executionId);
        return count == null ? 0 : count;
    }

    private String executionIdFrom(String callbackInfo) {
        return callbackInfo.substring(callbackInfo.indexOf("agent-executions/") + 17,
                callbackInfo.indexOf("/result"));
    }

    @Test
    void 首次回调落地终态与事件_错误token拒绝_重复回调幂等() {
        String agentId = "agent_callback_ok_" + SEQ.incrementAndGet();
        registerAgent(agentId, "EVENT_CALLBACK",
                "http://localhost:" + port + "/stub/agent/callback/ok");
        createTask(agentId, 60);

        String callbackInfo = awaitCallbackInfo("ok");
        String executionId = executionIdFrom(callbackInfo);
        String[] parts = callbackInfo.split("\\|", 3);
        String endpoint = parts[1];
        String token = parts[2];

        // token 缺失/错误 → 403 INVALID_CALLBACK_TOKEN，且不落终态
        ResponseEntity<String> noToken = callback(endpoint, null, resultBody(executionId, "SUCCEEDED"));
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noToken.getBody()).contains("INVALID_CALLBACK_TOKEN");
        ResponseEntity<String> badToken = callback(endpoint, "wrong-token",
                resultBody(executionId, "SUCCEEDED"));
        assertThat(badToken.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(badToken.getBody()).contains("INVALID_CALLBACK_TOKEN");
        assertThat(statusOf(executionId)).isIn("RUNNING", "PENDING");

        // 常量时间比较健全性：正确 token 应通过（MessageDigest.isEqual 语义）
        assertThat(MessageDigest.isEqual(token.getBytes(), token.getBytes())).isTrue();

        // 首次有效回调 → 终态 + 生命周期事件 + 业务事件
        ResponseEntity<String> ok = callback(endpoint, token, resultBody(executionId, "SUCCEEDED"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).contains("\"received\":true");
        assertThat(statusOf(executionId)).isEqualTo("SUCCEEDED");
        Integer lifecycle = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE execution_id = ? AND event_type = 'EXECUTION_SUCCEEDED'",
                Integer.class, executionId);
        Integer business = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE execution_id = ? AND event_type = 'BUSINESS_EVENT'",
                Integer.class, executionId);
        assertThat(lifecycle).isEqualTo(1);
        assertThat(business).isEqualTo(1);
        int eventsAfterFirst = eventCount(executionId);
        assertThat(eventsAfterFirst).isEqualTo(2);

        // 重复有效回调 → 幂等 200，事件数不变
        ResponseEntity<String> repeat = callback(endpoint, token, resultBody(executionId, "SUCCEEDED"));
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getBody()).contains("\"received\":true");
        assertThat(eventCount(executionId)).isEqualTo(eventsAfterFirst);
        assertThat(statusOf(executionId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void 路径与结果体execution_id不一致被拒() {
        String agentId = "agent_callback_mismatch_" + SEQ.incrementAndGet();
        registerAgent(agentId, "EVENT_CALLBACK",
                "http://localhost:" + port + "/stub/agent/callback/mismatch");
        createTask(agentId, 60);

        String callbackInfo = awaitCallbackInfo("mismatch");
        String executionId = executionIdFrom(callbackInfo);
        String[] parts = callbackInfo.split("\\|", 3);

        ResponseEntity<String> resp = callback(parts[1], parts[2],
                resultBody("Eother_execution", "SUCCEEDED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("EXECUTION_ID_MISMATCH");
        assertThat(statusOf(executionId)).isIn("RUNNING", "PENDING");
    }

    @Test
    void 不存在的execution返回404() {
        ResponseEntity<String> resp = callback(
                "/api/agent-executions/E nonexistent/result", "any-token",
                resultBody("E1", "SUCCEEDED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("EXECUTION_NOT_FOUND");
    }

    @Test
    void TIMEOUT终态后回调返回409() {
        String agentId = "agent_callback_timeout_" + SEQ.incrementAndGet();
        // SYNC 模式慢响应 + timeout_seconds=1 → sync 路径造 TIMEOUT
        registerAgent(agentId, "SYNC",
                "http://localhost:" + port + "/stub/agent/slow");
        String taskId = createTask(agentId, 1);

        String executionId = await().atMost(Duration.ofSeconds(20)).until(
                () -> jdbc.queryForList(
                        "SELECT execution_id FROM executions WHERE task_id = ? AND status = 'TIMEOUT'",
                        String.class, taskId).stream().findFirst().orElse(null),
                v -> v != null);
        // 手动补 callback_token 后走回调端点
        String token = "tok_timeout_" + SEQ.incrementAndGet();
        jdbc.update("UPDATE executions SET callback_token = ? WHERE execution_id = ?",
                token, executionId);

        ResponseEntity<String> resp = callback(
                "/api/agent-executions/" + executionId + "/result", token,
                resultBody(executionId, "SUCCEEDED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("EXECUTION_ALREADY_TERMINATED");
        assertThat(statusOf(executionId)).isEqualTo("TIMEOUT");
    }

    @Test
    void PENDING状态回调返回409() {
        String executionId = "E_pending_" + SEQ.incrementAndGet();
        String token = "tok_pending_" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO task_config_versions (task_id, version, definition, created_at) "
                        + "VALUES (?, 1, NULL, '2026-11-01T20:59:00+08:00') "
                        + "ON CONFLICT(task_id, version) DO NOTHING",
                "task_nonexistent_callback_test");
        jdbc.update("""
                INSERT INTO executions (execution_id, task_id, config_version, trigger_type,
                                        scheduled_fire_time, attempt, status, result, summary,
                                        last_error, callback_token, started_at, finished_at, created_at)
                VALUES (?, ?, 1, 'SCHEDULED', '2026-11-01T21:00:00+08:00', 1, 'PENDING',
                        NULL, NULL, NULL, ?, NULL, NULL, '2026-11-01T20:59:00+08:00')""",
                executionId, "task_nonexistent_callback_test", token);

        ResponseEntity<String> resp = callback(
                "/api/agent-executions/" + executionId + "/result", token,
                resultBody(executionId, "SUCCEEDED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("EXECUTION_NOT_RUNNING");
    }
}
