package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 工单 #11：阈值可配置（schedular.escalation.threshold=2 的专属 context，2 次失败即升级）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.quartz.auto-startup=true",
        "schedular.escalation.threshold=2"})
@Import(EscalationSemanticsTest.StubAgentController.class)
class EscalationThresholdTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "esc-t2-caller-key";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @org.junit.jupiter.api.AfterEach
    void pauseTask() {
        // SQLite 共享内存库写并发高会出现 SQLITE_LOCKED 丢触发，用完即暂停
        jdbc.update("UPDATE tasks SET status = 'PAUSED'");
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

    @org.junit.jupiter.api.BeforeEach
    void silenceStaleTasks() {
        // 共享内存库被多个缓存 context 的 Quartz 共用：先停掉历史 ACTIVE 任务，降低 SQLITE_LOCKED 丢触发概率
        jdbc.update("UPDATE tasks SET status = 'PAUSED' WHERE status = 'ACTIVE'");
    }

    @Test
    void 阈值为2时两次失败即升级() throws Exception {
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CALLER_KEY.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at)
                VALUES (?, ?, 'CALLER', 'tenant-esc-t2', 1, '2026-01-01T00:00:00+08:00')
                ON CONFLICT(key_hash) DO NOTHING""", hash, "caller-tenant-esc-t2");

        EscalationSemanticsTest.STUB_COUNTERS.put("esc-always-fail", new AtomicInteger());
        ResponseEntity<String> agentResp = exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", "agent_t11_t2",
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "http://localhost:1/unreachable",
                "resultMode", "SYNC"));
        assertThat(agentResp.getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<String> taskResp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "阈值2测试", "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", "agent_t11_t2", "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-esc-t2", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", 10, "retry_count", 0,
                        "max_concurrent_executions", 1)));
        assertThat(taskResp.getStatusCode().is2xxSuccessful()).isTrue();
        String taskId = new ObjectMapper().readTree(taskResp.getBody()).path("task_id").asText();

        // 链路不可达 → retry_count=0 立即穷尽 → FAILED 终态，计入连续失败
        try {
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    jdbc.queryForList(
                            "SELECT payload FROM events WHERE task_id = ? AND event_type = 'ESCALATED'",
                            String.class, taskId).size() >= 1);
        } catch (ConditionTimeoutException e) {
            Integer execs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM executions WHERE task_id = ?", Integer.class, taskId);
            throw new AssertionError("阈值2未在两次失败后升级，executions=" + execs, e);
        }
        String payload = jdbc.queryForObject(
                "SELECT payload FROM events WHERE task_id = ? AND event_type = 'ESCALATED' LIMIT 1",
                String.class, taskId);
        assertThat(payload).contains("\"consecutive_failures\":2");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tasks WHERE task_id = ?", String.class, taskId))
                .isEqualTo("ACTIVE");
    }
}
