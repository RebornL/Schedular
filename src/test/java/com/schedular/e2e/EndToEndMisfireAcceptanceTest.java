package com.schedular.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
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
 * 工单 #12 E2E：misfire 路径。阈值 0 需要专属 context（@Nested 内层属性会被外层
 * context 吞掉），故独立成类，模式复用 MisfireSemanticsTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.quartz.auto-startup=true",
        "schedular.retry-delay-seconds=1",
        "schedular.misfire-threshold-seconds=0"})
@Import(EndToEndMisfireAcceptanceTest.StubAgentController.class)
class EndToEndMisfireAcceptanceTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private String createdTaskId;

    @RestController
    static class StubAgentController {

        @PostMapping("/stub/agent/e2e-misfire")
        ResponseEntity<String> handle(@RequestBody String body) throws Exception {
            String execId = new ObjectMapper().readTree(body).path("execution_id").asText();
            return ResponseEntity.ok(new ObjectMapper().writeValueAsString(Map.of(
                    "execution_id", execId, "status", "SUCCEEDED",
                    "summary", "不应被执行", "report", Map.of())));
        }
    }

    private ResponseEntity<String> exchange(String method, String path, String apiKey, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", apiKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, h), String.class);
    }

    @AfterEach
    void pauseCreatedTask() {
        if (createdTaskId != null) {
            exchange("POST", "/api/tasks/" + createdTaskId + "/pause", CALLER_KEY, null);
        }
    }

    @Test
    void misfire路径阈值0时落MISFIRE事件且无execution() {
        assertThat(exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", "agent_e2e_misfire",
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "http://localhost:1/stub/unreachable",
                "resultMode", "SYNC")).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "e2e-misfire测试", "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", "agent_e2e_misfire", "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", 10, "retry_count", 2,
                        "max_concurrent_executions", 1)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            createdTaskId = new ObjectMapper().readTree(resp.getBody()).path("task_id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                List<Map<String, Object>> misfires = jdbc.queryForList(
                        "SELECT payload FROM events WHERE task_id = ? AND event_type = 'MISFIRE'",
                        createdTaskId);
                assertThat(misfires).isNotEmpty();
                assertThat(misfires.get(0).get("payload").toString())
                        .contains("scheduled_fire_time");
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("等待 MISFIRE 事件超时", e);
        }
        Integer executionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE task_id = ?", Integer.class, createdTaskId);
        assertThat(executionCount).isZero();
    }
}
