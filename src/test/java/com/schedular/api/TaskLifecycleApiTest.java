package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 工单 #4 缝一：任务六块校验与生命周期（创建→next_execution_time→pause→resume→stop→幂等 stop）、
 * tenant 校验、status 过滤、跨租户 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TaskLifecycleApiTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";
    private static final String OTHER_CALLER_KEY = "schedular-dev-caller-2";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

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

    /** 经 ADMIN API 注册 agent（两缝互喂约定：不直插业务数据）。 */
    private void registerAgent(String agentId) {
        exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agentId", agentId,
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "https://agent.example.com/invoke",
                "resultMode", "SYNC"));
    }

    private Map<String, Object> validTask(String name, String cron) {
        return Map.of(
                "task", Map.of("name", name, "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", "agent_for_tasks", "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", cron, "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", 60, "retry_count", 2, "max_concurrent_executions", 1));
    }

    private String createTask(String name, String cron) {
        return exchange("POST", "/api/tasks", CALLER_KEY, validTask(name, cron)).getBody();
    }

    @Test
    void 创建成功返回next_execution_time且与cron排期一致() {
        registerAgent("agent_for_tasks");
        String body = createTask("库存巡检", "*/30 * * * * ?");
        assertThat(body).contains("\"task_id\":\"T").contains("\"status\":\"ACTIVE\"")
                .contains("\"current_version\":1").contains("\"agent_id\":\"agent_for_tasks\"");
        String next = extractField(body, "next_execution_time");
        assertThat(next).isNotBlank();

        OffsetDateTime nextTime = OffsetDateTime.parse(next);
        // 秒级 cron */30：下一次触发必须落在 30 秒栅格上，且在 31 秒内
        assertThat(nextTime.getSecond() % 30).isZero();
        assertThat(nextTime.toInstant())
                .isAfter(OffsetDateTime.now().toInstant().minusSeconds(1))
                .isBefore(OffsetDateTime.now().toInstant().plusSeconds(31));

        // 详情接口同样返回 next_execution_time
        String taskId = extractField(body, "task_id").replace("\"", "");
        String detail = exchange("GET", "/api/tasks/" + taskId, CALLER_KEY, null).getBody();
        assertThat(detail).contains("库存巡检").contains("Asia/Shanghai")
                .contains("\"instruction\":\"检查库存风险\"").contains(next);
    }

    @Test
    void agent_id不存在时创建返回409() {
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY,
                validTask("无agent任务", "*/30 * * * * ?").entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e ->
                                "target".equals(e.getKey())
                                        ? Map.of("agent_id", "no_such_agent", "trigger_type", "HTTP_API")
                                        : e.getValue())));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("AGENT_NOT_FOUND");
    }

    @Test
    void 非法cron与非法timezone返回400() {
        registerAgent("agent_for_tasks");
        String badCron = exchange("POST", "/api/tasks", CALLER_KEY, validTask("坏cron", "0 0 25 * * ?")).getBody();
        assertThat(badCron).contains("INVALID_CRON");

        Map<String, Object> schedule = new java.util.HashMap<>(validTask("坏时区", "*/30 * * * * ?"));
        schedule.put("schedule", new java.util.HashMap<>(Map.of(
                "type", "CRON", "expression", "*/30 * * * * ?", "timezone", "Mars/Olympus")));
        String badTimezone = exchange("POST", "/api/tasks", CALLER_KEY, schedule).getBody();
        assertThat(badTimezone).contains("INVALID_TIMEZONE");
    }

    @Test
    void security租户与认证身份不符返回403() {
        registerAgent("agent_for_tasks");
        Map<String, Object> mismatch = new java.util.HashMap<>(validTask("租户不符", "*/30 * * * * ?"));
        mismatch.put("security", Map.of("tenant_id", "tenant-other", "owner_user_id", "owner-1"));
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, mismatch);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).contains("TENANT_MISMATCH");
    }

    @Test
    void pause后next为null且resume后恢复() {
        registerAgent("agent_for_tasks");
        String taskId = extractField(createTask("暂停恢复任务", "*/30 * * * * ?"), "task_id")
                .replace("\"", "");

        String paused = exchange("POST", "/api/tasks/" + taskId + "/pause", CALLER_KEY, null).getBody();
        assertThat(paused).contains("\"status\":\"PAUSED\"").contains("\"next_execution_time\":null");

        String resumed = exchange("POST", "/api/tasks/" + taskId + "/resume", CALLER_KEY, null).getBody();
        assertThat(resumed).contains("\"status\":\"ACTIVE\"");
        assertThat(extractField(resumed, "next_execution_time")).isNotBlank();
    }

    @Test
    void stop幂等且记录保留() {
        registerAgent("agent_for_tasks");
        String taskId = extractField(createTask("停用任务", "*/30 * * * * ?"), "task_id").replace("\"", "");

        ResponseEntity<String> first = exchange("DELETE", "/api/tasks/" + taskId, CALLER_KEY, null);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).contains("\"status\":\"STOPPED\"");

        ResponseEntity<String> again = exchange("DELETE", "/api/tasks/" + taskId, CALLER_KEY, null);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody()).contains("\"status\":\"STOPPED\"");

        // 无物理删除：记录仍可查
        assertThat(exchange("GET", "/api/tasks/" + taskId, CALLER_KEY, null).getBody())
                .contains("\"status\":\"STOPPED\"");
    }

    @Test
    void 列表按status过滤且仅见本租户() {
        registerAgent("agent_for_tasks");
        String taskId = extractField(createTask("过滤任务", "*/30 * * * * ?"), "task_id").replace("\"", "");
        exchange("POST", "/api/tasks/" + taskId + "/pause", CALLER_KEY, null);

        String activeList = exchange("GET", "/api/tasks?status=ACTIVE", CALLER_KEY, null).getBody();
        assertThat(activeList).doesNotContain(taskId);
        String pausedList = exchange("GET", "/api/tasks?status=PAUSED", CALLER_KEY, null).getBody();
        assertThat(pausedList).contains(taskId);
    }

    @Test
    void 跨租户访问返回404不泄露存在性() {
        // 第二个 CALLER key（tenant-other），按 #2 模式直接种 key（仅测试租户隔离用）
        String hash = HexFormat.of().formatHex(sha256(OTHER_CALLER_KEY));
        jdbc.update("""
                INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at)
                VALUES (?, 'other-caller', 'CALLER', 'tenant-other', 1, '2026-01-01T00:00:00+08:00')
                ON CONFLICT(key_hash) DO NOTHING""", hash);

        registerAgent("agent_for_tasks");
        String taskId = extractField(createTask("隔离任务", "*/30 * * * * ?"), "task_id").replace("\"", "");

        ResponseEntity<String> resp = exchange("GET", "/api/tasks/" + taskId, OTHER_CALLER_KEY, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("TASK_NOT_FOUND");
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extractField(String body, String field) {
        int key = body.indexOf("\"" + field + "\":");
        int start = key + field.length() + 3;
        char quote = body.charAt(start);
        if (quote == '"') {
            int end = body.indexOf('"', start + 1);
            return body.substring(start + 1, end);
        }
        int end = body.indexOf(',', start);
        if (end < 0) {
            end = body.indexOf('}', start);
        }
        return body.substring(start, end).trim();
    }
}
