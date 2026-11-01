package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * 工单 #13 B2：三个查询口租户参数统一口径（security/TenantScope）。
 * ADMIN 无 tenant → 400 MISSING_TENANT；ADMIN 带 tenant 只见该租户；
 * CALLER 显式他人 tenant → 403 TENANT_MISMATCH；单资源接口同口径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopeApiTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private String taskId;
    private long subscriptionId;

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

    @BeforeAll
    void setUp() {
        rest.exchange("/api/agents", HttpMethod.POST, new HttpEntity<>(Map.of(
                "agentId", "agent_tenant_scope",
                "agentVersion", "1.0.0",
                "triggerType", "HTTP_API",
                "endpointUrl", "https://agent.example.com/invoke",
                "resultMode", "SYNC"), headers(ADMIN_KEY)), String.class);
        String body = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "租户口径任务", "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", "agent_tenant_scope", "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", "0 0 * * * ?",
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1",
                        "permission_context_ref", "ctx-ref-1"),
                "execution_policy", Map.of("timeout_seconds", 60, "retry_count", 0,
                        "max_concurrent_executions", 1))).getBody();
        taskId = field(body, "task_id");
        subscriptionId = Long.parseLong(field(exchange("POST", "/api/subscriptions", CALLER_KEY,
                Map.of("callback_url", "https://sub.example.com/hook")).getBody(),
                "subscription_id"));
    }

    @Test
    void ADMIN无tenant三个查询口返回400() {
        assertThat(exchange("GET", "/api/tasks", ADMIN_KEY, null).getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("GET", "/api/subscriptions", ADMIN_KEY, null).getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("GET", "/api/events", ADMIN_KEY, null).getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("GET", "/api/tasks", ADMIN_KEY, null).getBody()).contains("MISSING_TENANT");
    }

    @Test
    void ADMIN带tenant只见该租户() throws Exception {
        ResponseEntity<String> tasks = exchange("GET", "/api/tasks?tenant=tenant-dev", ADMIN_KEY, null);
        assertThat(tasks.getStatusCode().value()).isEqualTo(200);
        for (JsonNode node : objectMapper.readTree(tasks.getBody())) {
            assertThat(node.path("tenant_id").asText()).isEqualTo("tenant-dev");
        }
        assertThat(tasks.getBody()).contains(taskId);

        ResponseEntity<String> subsResp = exchange("GET", "/api/subscriptions?tenant=tenant-dev", ADMIN_KEY, null);
        assertThat(subsResp.getStatusCode().value()).isEqualTo(200);
        assertThat(subsResp.getBody()).contains(String.valueOf(subscriptionId));

        // 其他租户：空列表而非报错
        ResponseEntity<String> empty = exchange("GET", "/api/tasks?tenant=tenant-none", ADMIN_KEY, null);
        assertThat(empty.getStatusCode().value()).isEqualTo(200);
        assertThat(empty.getBody()).isEqualTo("[]");

        // 单资源接口同口径：ADMIN 缺 tenant → 400，带 tenant → 200
        assertThat(exchange("GET", "/api/tasks/" + taskId, ADMIN_KEY, null).getStatusCode().value())
                .isEqualTo(400);
        assertThat(exchange("GET", "/api/tasks/" + taskId + "?tenant=tenant-dev", ADMIN_KEY, null)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void CALLER带他人tenant返回403() {
        assertThat(exchange("GET", "/api/tasks?tenant=tenant-other", CALLER_KEY, null)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(exchange("GET", "/api/subscriptions?tenant=tenant-other", CALLER_KEY, null)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(exchange("GET", "/api/events?tenant=tenant-other", CALLER_KEY, null)
                .getStatusCode().value()).isEqualTo(403);
        // 自己的 tenant（冗余声明）放行
        assertThat(exchange("GET", "/api/tasks?tenant=tenant-dev", CALLER_KEY, null)
                .getStatusCode().value()).isEqualTo(200);
    }

    private String field(String body, String name) {
        try {
            return objectMapper.readTree(body).path(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
