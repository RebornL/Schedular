package com.schedular.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #13 B1：EVENT_CALLBACK 执行超时兜底。Agent ack 后永不回调，
 * ExecutionTimeoutSweeper 须在 started_at + timeout_seconds 过期后：
 * 落 TIMEOUT 终态、发 EXECUTION_TIMEOUT 事件、释放并发槽位（下一轮触发正常建行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.quartz.auto-startup=true",
        "schedular.execution-timeout-sweep-interval=200ms"})
@Import(ExecutionTimeoutSweeperTest.StubAgentController.class)
class ExecutionTimeoutSweeperTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @RestController
    static class StubAgentController {

        /** EVENT_CALLBACK 模式：只 ack，永不回调结果。 */
        @PostMapping("/stub/agent/ack-silent")
        ResponseEntity<Map<String, Object>> ack(@RequestBody JsonNode body) {
            String executionId = body.path("execution_id").asText();
            return ResponseEntity.ok(Map.of("accepted", true, "execution_id", executionId));
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

    @Test
    void 超时兜底落TIMEOUT发事件并释放槽位() {
        exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", "agent_ack_silent_" + SEQ.incrementAndGet(),
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "http://localhost:" + port + "/stub/agent/ack-silent",
                "resultMode", "EVENT_CALLBACK"));
        String agentId = "agent_ack_silent_" + SEQ.get();
        String body = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "超时兜底任务", "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "永不回调"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of("timeout_seconds", 1, "retry_count", 0,
                        "max_concurrent_executions", 1))).getBody();
        String taskId;
        // 提取 task_id
        try {
            taskId = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("task_id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // 兜底：RUNNING 超 timeout_seconds=1 未回调 → TIMEOUT 终态
        await().atMost(Duration.ofSeconds(15)).until(() ->
                count("SELECT COUNT(*) FROM executions WHERE task_id = ? AND status = 'TIMEOUT'",
                        taskId) >= 1);
        Integer timeoutRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ? AND status = 'TIMEOUT' "
                        + "AND last_error = '执行超时（未收到回调）' AND finished_at IS NOT NULL",
                Integer.class, taskId);
        assertThat(timeoutRow).isEqualTo(1);

        // 终态路径：EXECUTION_TIMEOUT 事件已产生
        await().atMost(Duration.ofSeconds(5)).until(() ->
                count("SELECT COUNT(*) FROM events WHERE event_type = 'EXECUTION_TIMEOUT' "
                        + "AND task_id = ?", taskId) >= 1);

        // 槽位释放：TIMEOUT 后下一轮到点触发正常建行（总 execution 行数 > 1）
        await().atMost(Duration.ofSeconds(15)).until(() ->
                count("SELECT COUNT(*) FROM executions WHERE task_id = ?", taskId) >= 2);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
