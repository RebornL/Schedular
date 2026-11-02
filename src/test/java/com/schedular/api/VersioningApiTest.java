package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #6 缝一：任务配置版本化。更新后版本+1 且旧执行仍引用旧版本、
 * 更新后 next_execution_time 变化、PAUSED 中更新恢复后按新版执行、非法 cron 更新被拒且版本不变。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.quartz.auto-startup=true")
@Import(VersioningApiTest.StubAgentController.class)
class VersioningApiTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String CRON_EVERY_SECOND = "*/1 * * * * ?";

    private static final AtomicInteger SEQ = new AtomicInteger();

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

        @PostMapping("/stub/agent/versioning")
        ResponseEntity<String> handle(@RequestBody String body)
                throws Exception {
            String execId = new ObjectMapper().readTree(body).path("execution_id").asText();
            return ResponseEntity.ok(new ObjectMapper().writeValueAsString(Map.of(
                    "execution_id", execId,
                    "status", "SUCCEEDED",
                    "summary", "版本化巡检完成",
                    "business_events", List.of(),
                    "report", Map.of())));
        }
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", apiKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> exchange(String method, String path, Object body) {
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers(CALLER_KEY)), String.class);
    }

    private void registerAgent(String agentId) {
        ResponseEntity<String> resp = rest.exchange("/api/agents", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "agent_id", agentId,
                        "agent_version", "1.0.0",
                        "trigger_type", "HTTP_API",
                        "endpoint_url", "http://localhost:" + port + "/stub/agent/versioning",
                        "result_mode", "SYNC"), headers(ADMIN_KEY)), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    /** 全量六块请求体：更新与创建同构（PUT 复用创建校验）。 */
    private Map<String, Object> taskBody(String name, String agentId, String cron) {
        return Map.of(
                "task", Map.of("name", name, "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", cron,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", 60, "retry_count", 2,
                        "max_concurrent_executions", 1));
    }

    private String create(String agentId, String cron) {
        int seq = SEQ.incrementAndGet();
        ResponseEntity<String> resp = exchange("POST", "/api/tasks",
                taskBody("版本化测试-" + seq, agentId, cron));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return field(resp.getBody(), "task_id");
    }

    private ResponseEntity<String> put(String taskId, Map<String, Object> body) {
        return exchange("PUT", "/api/tasks/" + taskId, body);
    }

    private ResponseEntity<String> get(String taskId) {
        return exchange("GET", "/api/tasks/" + taskId, null);
    }

    private List<JsonNode> executions(String taskId) {
        ResponseEntity<String> resp = exchange("GET", "/api/tasks/" + taskId + "/executions", null);
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

    private JsonNode awaitExecution(String taskId, int configVersion) {
        try {
            return await().atMost(Duration.ofSeconds(20)).until(() -> executions(taskId).stream()
                    .filter(e -> e.path("config_version").asInt() == configVersion)
                    .findFirst().orElse(null), e -> e != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("等待 config_version=" + configVersion + " 的执行超时，当前: "
                    + executions(taskId), e);
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
    void 更新后版本递增且旧执行仍引用旧版本() {
        String agentId = "agent_versioning_" + SEQ.incrementAndGet();
        registerAgent(agentId);
        String taskId = create(agentId, CRON_EVERY_SECOND);

        // 旧版本先触发一次，in-flight/已落库执行引用 v1
        JsonNode oldExecution = awaitExecution(taskId, 1);
        assertThat(oldExecution.path("config_version").asInt()).isEqualTo(1);

        // 更新配置（cron 仍每秒，instruction 改变）→ v2 快照，后续触发走 v2
        ResponseEntity<String> resp = put(taskId,
                taskBody("版本化测试-更新", agentId, CRON_EVERY_SECOND));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"current_version\":2");

        JsonNode newExecution = awaitExecution(taskId, 2);

        // 旧执行仍引用 v1，新执行引用 v2；快照表只增不减
        assertThat(oldExecution.path("config_version").asInt()).isEqualTo(1);
        assertThat(newExecution.path("config_version").asInt()).isEqualTo(2);
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_config_versions WHERE task_id = ?",
                Integer.class, taskId);
        assertThat(snapshotCount).isEqualTo(2);
    }

    @Test
    void 更新后next_execution_time随新cron变化() {
        String agentId = "agent_versioning_next_" + SEQ.incrementAndGet();
        registerAgent(agentId);
        String taskId = create(agentId, "0 0 3 * * ?");
        String nextBefore = field(get(taskId).getBody(), "next_execution_time");
        assertThat(nextBefore).contains("T03:00");

        ResponseEntity<String> resp = put(taskId,
                taskBody("版本化测试-next", agentId, "0 0 5 * * ?"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String nextAfter = field(resp.getBody(), "next_execution_time");
        assertThat(nextAfter).contains("T05:00").isNotEqualTo(nextBefore);
    }

    @Test
    void 暂停中更新恢复后按新版本执行() {
        String agentId = "agent_versioning_paused_" + SEQ.incrementAndGet();
        registerAgent(agentId);
        String taskId = create(agentId, CRON_EVERY_SECOND);

        assertThat(exchange("POST", "/api/tasks/" + taskId + "/pause", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // PAUSED 期间更新：版本照常递增，next 为 null
        ResponseEntity<String> resp = put(taskId,
                taskBody("版本化测试-暂停更新", agentId, CRON_EVERY_SECOND));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"current_version\":2");
        assertThat(field(resp.getBody(), "next_execution_time")).isEmpty();

        assertThat(exchange("POST", "/api/tasks/" + taskId + "/resume", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // 恢复后按 v2 执行
        JsonNode execution = awaitExecution(taskId, 2);
        assertThat(execution.path("config_version").asInt()).isEqualTo(2);
    }

    @Test
    void 非法cron的更新被拒绝且版本不变() {
        String agentId = "agent_versioning_invalid_" + SEQ.incrementAndGet();
        registerAgent(agentId);
        String taskId = create(agentId, CRON_EVERY_SECOND);

        ResponseEntity<String> resp = put(taskId,
                taskBody("版本化测试-非法", agentId, "not a cron"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("INVALID_CRON");

        ResponseEntity<String> after = get(taskId);
        assertThat(field(after.getBody(), "current_version")).isEqualTo("1");
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_config_versions WHERE task_id = ?",
                Integer.class, taskId);
        assertThat(snapshotCount).isEqualTo(1);
    }
}
