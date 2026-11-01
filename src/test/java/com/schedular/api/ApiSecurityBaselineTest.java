package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 缝一（对外 API）基线：上下文加载 + 迁移建齐 8 张表 + API Key 401/403 行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityBaselineTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "tasks", "task_config_versions", "executions", "events",
            "event_deliveries", "subscriptions", "agents", "api_keys");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private ResponseEntity<String> get(String path, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void 上下文加载且迁移应用后八张业务表全部建立() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).containsAll(EXPECTED_TABLES);
    }

    @Test
    void 无APIKey访问受保护接口返回401() {
        ResponseEntity<String> resp = get("/api/tasks", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void 无效APIKey访问受保护接口返回401() {
        ResponseEntity<String> resp = get("/api/tasks", "wrong-key");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void devKey访问任务接口通过认证门槛() {
        // taskmanager 未实现，404 即为通过认证（非 401）
        assertThat(get("/api/tasks", "schedular-dev-key").getStatusCode().value()).isNotEqualTo(401);
    }

    @Test
    void CALLER角色访问agents接口被拒绝返回403() {
        ResponseEntity<String> resp = get("/api/agents", "schedular-dev-caller");
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).contains("FORBIDDEN");
    }

    @Test
    void ADMIN角色访问agents接口通过角色门槛() {
        assertThat(get("/api/agents", "schedular-dev-key").getStatusCode().value()).isNotEqualTo(403);
    }
}
