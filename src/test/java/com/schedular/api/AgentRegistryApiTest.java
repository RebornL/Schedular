package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 工单 #3 Agent Registry：ADMIN 全流程、唯一性、校验、auth_headers 只写不读、CALLER 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentRegistryApiTest {

    private static final String ADMIN_KEY = "schedular-dev-key";
    private static final String CALLER_KEY = "schedular-dev-caller";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private HttpHeaders headers(String apiKey, MediaType contentType) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", apiKey);
        h.setContentType(contentType);
        return h;
    }

    private ResponseEntity<String> exchange(String method, String path, String apiKey, Object body) {
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers(apiKey, MediaType.APPLICATION_JSON)), String.class);
    }

    private Map<String, Object> validAgent(String agentId) {
        return Map.of(
                "agent_id", agentId,
                "agent_version", "1.0.0",
                "trigger_type", "HTTP_API",
                "endpoint_url", "https://agent.example.com/invoke",
                "result_mode", "SYNC",
                "authHeaders", Map.of("X-Internal-Token", "secret-token"));
    }

    @Test
    void ADMIN注册后可查询单个与列表并更新与停用() {
        String agentId = "agent_crud_flow";
        ResponseEntity<String> created = exchange("POST", "/api/agents", ADMIN_KEY, validAgent(agentId));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getBody()).contains("agent_crud_flow").contains("SYNC").contains("\"enabled\":true");

        assertThat(exchange("GET", "/api/agents/" + agentId, ADMIN_KEY, null).getBody())
                .contains("agent_crud_flow");

        ResponseEntity<String> list = exchange("GET", "/api/agents", ADMIN_KEY, null);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains(agentId);

        Map<String, Object> updated = Map.of(
                "agent_version", "1.1.0",
                "trigger_type", "HTTP_API",
                "endpoint_url", "https://agent.example.com/v2",
                "result_mode", "EVENT_CALLBACK");
        ResponseEntity<String> put = exchange("PUT", "/api/agents/" + agentId, ADMIN_KEY, updated);
        assertThat(put.getStatusCode().value()).isEqualTo(200);
        assertThat(put.getBody()).contains("1.1.0").contains("EVENT_CALLBACK").contains("/v2");

        ResponseEntity<String> deleted = exchange("DELETE", "/api/agents/" + agentId, ADMIN_KEY, null);
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        assertThat(deleted.getBody()).contains("\"enabled\":false");

        // 停用后记录仍在（无物理删除），且更新返回体保留 enabled=false
        Integer enabled = jdbc.queryForObject(
                "SELECT enabled FROM agents WHERE agent_id = ?", Integer.class, agentId);
        assertThat(enabled).isZero();
        assertThat(exchange("PUT", "/api/agents/" + agentId, ADMIN_KEY, updated).getBody())
                .contains("\"enabled\":false");
    }

    @Test
    void 重复agentId注册返回409且错误码为AGENT_ALREADY_EXISTS() {
        exchange("POST", "/api/agents", ADMIN_KEY, validAgent("agent_dup"));
        ResponseEntity<String> again = exchange("POST", "/api/agents", ADMIN_KEY, validAgent("agent_dup"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("AGENT_ALREADY_EXISTS");
    }

    @Test
    void 非法resultMode返回400() {
        Map<String, Object> bad = Map.of(
                "agent_id", "agent_bad_mode",
                "trigger_type", "HTTP_API",
                "endpoint_url", "https://agent.example.com/invoke",
                "result_mode", "ASYNC");
        ResponseEntity<String> resp = exchange("POST", "/api/agents", ADMIN_KEY, bad);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("INVALID_RESULT_MODE");
    }

    @Test
    void 非法triggerType与非法endpointUrl返回400() {
        Map<String, Object> badTrigger = Map.of(
                "agent_id", "agent_bad_trigger",
                "trigger_type", "WEBHOOK",
                "endpoint_url", "https://agent.example.com/invoke",
                "result_mode", "SYNC");
        assertThat(exchange("POST", "/api/agents", ADMIN_KEY, badTrigger).getBody())
                .contains("INVALID_TRIGGER_TYPE");

        Map<String, Object> badUrl = Map.of(
                "agent_id", "agent_bad_url",
                "trigger_type", "HTTP_API",
                "endpoint_url", "not-a-url",
                "result_mode", "SYNC");
        assertThat(exchange("POST", "/api/agents", ADMIN_KEY, badUrl).getBody())
                .contains("INVALID_ENDPOINT_URL");
    }

    @Test
    void 任何响应都不回显authHeaders() {
        exchange("POST", "/api/agents", ADMIN_KEY, validAgent("agent_secret"));
        for (String path : new String[] {"/api/agents", "/api/agents/agent_secret"}) {
            String body = exchange("GET", path, ADMIN_KEY, null).getBody();
            assertThat(body).doesNotContain("X-Internal-Token").doesNotContain("secret-token").doesNotContain("authHeaders");
        }
    }

    @Test
    void 查询不存在的agent返回404() {
        ResponseEntity<String> resp = exchange("GET", "/api/agents/no_such_agent", ADMIN_KEY, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("AGENT_NOT_FOUND");
    }

    @Test
    void CALLER对agents的增删改查均被拒绝返回403() {
        String body = "{\"agentId\":\"x\",\"triggerType\":\"HTTP_API\",\"endpointUrl\":\"https://a.example.com\",\"resultMode\":\"SYNC\"}";
        assertThat(exchange("POST", "/api/agents", CALLER_KEY, body).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange("GET", "/api/agents", CALLER_KEY, null).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange("PUT", "/api/agents/x", CALLER_KEY, body).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange("DELETE", "/api/agents/x", CALLER_KEY, null).getStatusCode().value()).isEqualTo(403);
    }
}
