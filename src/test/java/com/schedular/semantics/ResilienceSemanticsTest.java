package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * 工单 #10 缝二：调度韧性语义（misfire / SKIPPED_CONCURRENT / 链路重试）。
 * 断言走 events / executions 表直查（无查询接口覆盖的部分），行为入口全部经真实 Quartz 触发。
 * 重试 delay 压到 1s（schedular.retry-delay-seconds）以缩短异步等待。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.quartz.auto-startup=true",
        "schedular.retry-delay-seconds=1"})
@Import(ResilienceSemanticsTest.StubAgentController.class)
class ResilienceSemanticsTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    /** stub 可变状态：flaky 场景前 2 次 500，之后 200。 */
    static final Map<String, AtomicInteger> STUB_COUNTERS = new ConcurrentHashMap<>();

    @RestController
    static class StubAgentController {

        @PostMapping("/stub/agent/{scenario}")
        ResponseEntity<String> handle(@PathVariable String scenario, @RequestBody String body)
                throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String execId = mapper.readTree(body).path("execution_id").asText();
            return switch (scenario) {
                case "sync-ok" -> ResponseEntity.ok(toJson(Map.of(
                        "execution_id", execId, "status", "SUCCEEDED",
                        "summary", "巡检完成", "report", Map.of())));
                case "sync-fail" -> ResponseEntity.ok(toJson(Map.of(
                        "execution_id", execId, "status", "FAILED",
                        "summary", "Agent 内部业务失败")));
                case "sync-timeout" -> {
                    Thread.sleep(8000);
                    yield ResponseEntity.ok(toJson(Map.of(
                            "execution_id", execId, "status", "SUCCEEDED",
                            "summary", "来得太晚")));
                }
                case "flaky" -> {
                    int n = STUB_COUNTERS.computeIfAbsent("flaky", k -> new AtomicInteger())
                            .incrementAndGet();
                    if (n <= 2) {
                        yield ResponseEntity.status(500)
                                .body("{\"code\":\"AGENT_ERROR\",\"message\":\"stub 故障\"}");
                    }
                    yield ResponseEntity.ok(toJson(Map.of(
                            "execution_id", execId, "status", "SUCCEEDED",
                            "summary", "第 " + n + " 次成功", "report", Map.of())));
                }
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
                "agent_id", agentId,
                "agent_version", "1.0.0",
                "trigger_type", "HTTP_API",
                "endpoint_url", "http://localhost:" + port + "/stub/agent/" + scenario,
                "result_mode", resultMode));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private String createTask(String agentId, int timeoutSeconds, int retryCount,
                              int maxConcurrentExecutions) {
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "韧性测试-" + agentId,
                        "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", timeoutSeconds, "retry_count", retryCount,
                        "max_concurrent_executions", maxConcurrentExecutions)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return field(resp.getBody(), "task_id");
    }

    /** 该 task 最早创建（rowid 最小）的 execution 行，即首个触发行。 */
    private Map<String, Object> earliestExecution(String taskId) {
        return jdbc.queryForMap(
                "SELECT execution_id, attempt, status, last_error FROM executions "
                        + "WHERE task_id = ? ORDER BY rowid ASC LIMIT 1", taskId);
    }

    private Map<String, Object> awaitEarliestExecution(String taskId, String expectedStatus,
                                                       Duration timeout) {
        try {
            return await().atMost(timeout).until(() -> {
                Map<String, Object> row = earliestExecution(taskId);
                return expectedStatus.equals(row.get("status")) ? row : null;
            }, row -> row != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("等待状态 " + expectedStatus + " 超时，当前: "
                    + safeEarliest(taskId), e);
        }
    }

    private Map<String, Object> safeEarliest(String taskId) {
        try {
            return earliestExecution(taskId);
        } catch (RuntimeException e) {
            return Map.of("status", "NO_ROW");
        }
    }

    private List<Map<String, Object>> events(String taskId, String eventType) {
        return jdbc.queryForList(
                "SELECT event_type, severity, execution_id, payload FROM events "
                        + "WHERE task_id = ? AND event_type = ?", taskId, eventType);
    }

    private static String field(String body, String name) {
        try {
            return new ObjectMapper().readTree(body).path(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 并发达到上限时跳过并记SKIPPED_CONCURRENT不新建execution() {
        registerAgent("agent_t10_skip", "sync-timeout", "SYNC");
        String taskId = createTask("agent_t10_skip", 10, 0, 1);

        await().atMost(Duration.ofSeconds(10)).until(() ->
                "RUNNING".equals(safeEarliest(taskId).get("status")));
        String runningId = (String) earliestExecution(taskId).get("execution_id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Map<String, Object>> skipped = events(taskId, "SKIPPED_CONCURRENT");
            assertThat(skipped).isNotEmpty();
            assertThat(skipped.get(0).get("payload").toString())
                    .contains("running_execution_id")
                    .contains(runningId);
        });
        Integer executionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ?", Integer.class, taskId);
        assertThat(executionCount).isEqualTo(1);
    }

    @Test
    void 链路故障按attempt重试至成功同一execution_id() {
        STUB_COUNTERS.put("flaky", new AtomicInteger());
        registerAgent("agent_t10_retry", "flaky", "SYNC");
        String taskId = createTask("agent_t10_retry", 10, 2, 1);

        Map<String, Object> row = awaitEarliestExecution(taskId, "SUCCEEDED",
                Duration.ofSeconds(30));
        String executionId = (String) row.get("execution_id");
        assertThat(row.get("attempt")).isEqualTo(3);
        assertThat(row.get("last_error")).isNotNull();
        // 同一 execution_id：重试不新建行
        Integer executionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ? AND execution_id = ?",
                Integer.class, taskId, executionId);
        assertThat(executionCount).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events(taskId, "EXECUTION_SUCCEEDED"))
                        .anyMatch(e -> executionId.equals(e.get("execution_id"))
                                && e.get("payload").toString().contains("\"attempt\":3")));
    }

    @Test
    void 重试穷尽落FAILED终态并产生EXECUTION_FAILED事件() {
        registerAgent("agent_t10_exhaust", "always500", "SYNC");
        String taskId = createTask("agent_t10_exhaust", 10, 1, 1);

        Map<String, Object> row = awaitEarliestExecution(taskId, "FAILED",
                Duration.ofSeconds(30));
        String executionId = (String) row.get("execution_id");
        assertThat(row.get("attempt")).isEqualTo(2);
        assertThat((String) row.get("last_error")).contains("重试穷尽");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events(taskId, "EXECUTION_FAILED"))
                        .anyMatch(e -> executionId.equals(e.get("execution_id"))
                                && e.get("payload").toString().contains("重试穷尽")
                                && e.get("payload").toString().contains("\"attempt\":2")));
    }

    @Test
    void 业务FAILED永不重试attempt恒为1() throws Exception {
        registerAgent("agent_t10_bizfail", "sync-fail", "SYNC");
        String taskId = createTask("agent_t10_bizfail", 10, 2, 1);

        Map<String, Object> row = awaitEarliestExecution(taskId, "FAILED",
                Duration.ofSeconds(15));
        assertThat(row.get("attempt")).isEqualTo(1);
        // 重试 delay=1s：若被错误重试，attempt 早已 >1
        Thread.sleep(3000);
        Map<String, Object> after = earliestExecution(taskId);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("attempt")).isEqualTo(1);
    }
}
