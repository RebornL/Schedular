package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * 工单 #11 缝二：连续失败升级 ESCALATED 语义。
 * 断言走 events / tasks 表直查；订阅推送经真实订阅注册 + stub 订阅方验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.quartz.auto-startup=true")
@Import({EscalationSemanticsTest.StubAgentController.class,
        EscalationSemanticsTest.StubSubscriberController.Handler.class})
class EscalationSemanticsTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "esc-caller-key";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    /** stub 可变状态：按 scenario 计数，控制混合/超时序列。 */
    static final Map<String, AtomicInteger> STUB_COUNTERS = new ConcurrentHashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void silenceStaleTasks() {
        // 共享内存库被多个缓存 context 的 Quartz 共用：先停掉历史 ACTIVE 任务，降低 SQLITE_LOCKED 丢触发概率
        jdbc.update("UPDATE tasks SET status = 'PAUSED' WHERE status = 'ACTIVE'");
    }

    @RestController
    static class StubAgentController {

        @PostMapping("/stub/agent/{scenario}")
        ResponseEntity<String> handle(@PathVariable String scenario, @RequestBody String body)
                throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String execId = mapper.readTree(body).path("execution_id").asText();
            int call = STUB_COUNTERS.computeIfAbsent(scenario, k -> new AtomicInteger())
                    .incrementAndGet();
            return switch (scenario) {
                case "esc-always-fail" -> ok(execId, "FAILED");
                case "esc-mixed" -> ok(execId, call == 4 || call > 7 ? "SUCCEEDED" : "FAILED");
                case "esc-timeout-then-fail" -> {
                    if (call == 1) {
                        // 超过任务 timeout_seconds=2，触发 TIMEOUT 终态
                        Thread.sleep(3000);
                        yield ok(execId, "SUCCEEDED");
                    }
                    yield ok(execId, "FAILED");
                }
                default -> ResponseEntity.status(500)
                        .body("{\"code\":\"AGENT_ERROR\",\"message\":\"stub 故障\"}");
            };
        }

        private static ResponseEntity<String> ok(String execId, String status) {
            try {
                String body = new ObjectMapper().writeValueAsString(Map.of(
                        "execution_id", execId, "status", status,
                        "summary", "stub 结果 " + status, "report", Map.of()));
                return ResponseEntity.ok(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** stub 订阅方：记录推送 body（含 event_type/task_id/payload）。 */
    static class StubSubscriberController {

        static final Map<String, List<Recorded>> REQUESTS = new ConcurrentHashMap<>();

        record Recorded(String body) { }

        @RestController
        static class Handler {

            @PostMapping("/stub/subscriber/{key}")
            ResponseEntity<String> handle(@PathVariable String key, @RequestBody String body) {
                REQUESTS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(new Recorded(body));
                return ResponseEntity.ok("{\"received\":true}");
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

    private void seedCaller() {
        String hash;
        try {
            hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(CALLER_KEY.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at)
                VALUES (?, ?, 'CALLER', 'tenant-esc', 1, '2026-01-01T00:00:00+08:00')
                ON CONFLICT(key_hash) DO NOTHING""", hash, "caller-tenant-esc");
    }

    private void registerAgent(String agentId, String scenario) {
        ResponseEntity<String> resp = exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", agentId,
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "http://localhost:" + port + "/stub/agent/" + scenario,
                "resultMode", "SYNC"));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private String createTask(String agentId, int timeoutSeconds) {
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "升级测试-" + agentId,
                        "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-esc", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", timeoutSeconds, "retry_count", 0,
                        "max_concurrent_executions", 1)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String taskId = field(resp.getBody(), "task_id");
        return taskId;
    }

    private void registerSubscription(String key, String taskId) {
        ResponseEntity<String> resp = exchange("POST", "/api/subscriptions", CALLER_KEY, Map.of(
                "callback_url", "http://localhost:" + port + "/stub/subscriber/" + key,
                "filter_task_ids", List.of(taskId)));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    private List<Map<String, Object>> events(String taskId, String eventType) {
        return jdbc.queryForList(
                "SELECT execution_id, payload FROM events "
                        + "WHERE task_id = ? AND event_type = ?", taskId, eventType);
    }

    private String taskStatus(String taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM tasks WHERE task_id = ?", String.class, taskId);
    }

    private void awaitEscalated(String taskId, int expectedCount) {
        try {
            await().atMost(Duration.ofSeconds(20)).until(
                    () -> events(taskId, "ESCALATED").size() >= expectedCount);
        } catch (ConditionTimeoutException e) {
            Integer execs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM executions WHERE task_id = ?", Integer.class, taskId);
            List<Map<String, Object>> statuses = jdbc.queryForList(
                    "SELECT execution_id, status FROM executions WHERE task_id = ? "
                            + "ORDER BY rowid ASC", taskId);
            throw new AssertionError("等待第 " + expectedCount + " 条 ESCALATED 超时，executions="
                    + execs + ", statuses=" + statuses + ", escalations="
                    + events(taskId, "ESCALATED"), e);
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
    void 连续失败3次第3次产生ESCALATED且订阅方收到() {
        seedCaller();
        registerAgent("agent_t11_fail", "esc-always-fail");
        String taskId = createTask("agent_t11_fail", 10);
        registerSubscription("esc-sub-1", taskId);

        awaitEscalated(taskId, 1);
        List<Map<String, Object>> escalated = events(taskId, "ESCALATED");
        assertThat(escalated).hasSize(1);
        assertThat(escalated.get(0).get("payload").toString())
                .contains("\"consecutive_failures\":3");
        // execution_id 为当次（最后一次失败的 execution）
        String lastExecId = jdbc.queryForObject(
                "SELECT execution_id FROM executions WHERE task_id = ? "
                        + "ORDER BY rowid DESC LIMIT 1", String.class, taskId);
        assertThat(escalated.get(0).get("execution_id")).isEqualTo(lastExecId);
        // 进入正常订阅推送：订阅方收到 ESCALATED
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(StubSubscriberController.REQUESTS.getOrDefault("esc-sub-1", List.of()))
                        .anyMatch(r -> r.body().contains("ESCALATED")
                                && r.body().contains(taskId)));
        // 不自动暂停
        assertThat(taskStatus(taskId)).isEqualTo("ACTIVE");
    }

    @Test
    void 中途成功清零后需重新累计() {
        seedCaller();
        STUB_COUNTERS.put("esc-mixed", new AtomicInteger());
        registerAgent("agent_t11_mixed", "esc-mixed");
        String taskId = createTask("agent_t11_mixed", 10);

        // 序列: 失败,失败,失败,成功(清零),失败,失败,失败 → 两次 ESCALATED，计数值都是 3
        awaitEscalated(taskId, 2);
        List<Map<String, Object>> escalated = events(taskId, "ESCALATED");
        assertThat(escalated).hasSize(2);
        assertThat(escalated).allMatch(e ->
                e.get("payload").toString().contains("\"consecutive_failures\":3"));
        // 两次升级之间的成功事件存在
        assertThat(events(taskId, "EXECUTION_SUCCEEDED")).isNotEmpty();
        Integer successCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ? AND status = 'SUCCEEDED'",
                Integer.class, taskId);
        assertThat(successCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void TIMEOUT也计入连续失败() {
        seedCaller();
        STUB_COUNTERS.put("esc-timeout-then-fail", new AtomicInteger());
        registerAgent("agent_t11_timeout", "esc-timeout-then-fail");
        String taskId = createTask("agent_t11_timeout", 2);

        awaitEscalated(taskId, 1);
        // 第 1 次 TIMEOUT + 第 2、3 次 FAILED = 计数 3
        Integer timeoutCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ? AND status = 'TIMEOUT'",
                Integer.class, taskId);
        assertThat(timeoutCount).isGreaterThanOrEqualTo(1);
        assertThat(events(taskId, "ESCALATED").get(0).get("payload").toString())
                .contains("\"consecutive_failures\":3");
        assertThat(taskStatus(taskId)).isEqualTo("ACTIVE");
    }
}
