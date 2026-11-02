package com.schedular.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #12 缝间 E2E 验收：注册 Agent → 注册任务 → 到点触发 → 受理/结果回传（SYNC 与
 * EVENT_CALLBACK 两路径）→ 事件按订阅 HMAC 签名推送，覆盖失败升级与版本化穿插；
 * 末位用例为数据一致性检查（验收工具，唯一允许 JDBC 直查断言的环节）。
 * 全部注册走 HTTP 缝一，触发链路真实走 HTTP 缝二（复用 semantics/api 既有 stub 模式）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.quartz.auto-startup=true",
        "schedular.retry-delay-seconds=1"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({EndToEndAcceptanceTest.StubAgentController.class,
        EndToEndAcceptanceTest.StubSubscriberController.Handler.class})
class EndToEndAcceptanceTest {

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

    /** 本用例创建的任务，@AfterEach 统一经 API 暂停，降低对共享库其他 context 的 SQLITE_LOCKED 干扰。 */
    private final List<String> createdTaskIds = new ArrayList<>();

    // ---------- stub Agent（ack 捕获回调信息 + 可控结果） ----------

    /** scenario -> "executionId|endpoint|token"，stub ack 时捕获下发地址与凭证。 */
    static final Map<String, String> CALLBACKS = new ConcurrentHashMap<>();
    /** scenario -> 计数器，控制连续失败序列。 */
    static final Map<String, AtomicInteger> STUB_COUNTERS = new ConcurrentHashMap<>();

    @RestController
    static class StubAgentController {

        @PostMapping("/stub/agent/{scenario}")
        ResponseEntity<String> handle(@PathVariable String scenario, @RequestBody JsonNode body) {
            String execId = body.path("execution_id").asText();
            int call = STUB_COUNTERS.computeIfAbsent(scenario, k -> new AtomicInteger())
                    .incrementAndGet();
            return switch (scenario) {
                case "e2e-sync-ok" -> ok(execId, "SUCCEEDED", true);
                case "e2e-always-fail" -> ok(execId, "FAILED", false);
                default -> ResponseEntity.status(500)
                        .body("{\"code\":\"AGENT_ERROR\",\"message\":\"stub 故障\"}");
            };
        }

        /** EVENT_CALLBACK 模式：立即 ack，捕获 callback_endpoint + callback_token 供测试侧回调。 */
        @PostMapping("/stub/agent/callback/{scenario}")
        ResponseEntity<Map<String, Object>> ack(@PathVariable String scenario,
                                                @RequestBody JsonNode body) {
            String executionId = body.path("execution_id").asText();
            CALLBACKS.put(scenario,
                    executionId + "|"
                            + body.path("callback").path("callback_endpoint").asText()
                            + "|" + body.path("callback").path("callback_token").asText());
            return ResponseEntity.ok(Map.of("accepted", true, "execution_id", executionId));
        }

        private static ResponseEntity<String> ok(String execId, String status,
                                                 boolean withBusinessEvent) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> result = withBusinessEvent
                        ? Map.of(
                                "execution_id", execId,
                                "status", status,
                                "summary", "巡检完成，一切正常",
                                "business_events", List.of(Map.of(
                                        "type", "INVENTORY_RISK", "severity", "HIGH",
                                        "title", "M001 缺料风险", "content", Map.of(),
                                        "dedup_key", "M001-LOW_STOCK")),
                                "report", Map.of())
                        : Map.of(
                                "execution_id", execId,
                                "status", status,
                                "summary", "Agent 内部业务失败",
                                "report", Map.of());
                return ResponseEntity.ok(mapper.writeValueAsString(result));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------- stub 订阅方（记录推送 body/headers） ----------

    static class StubSubscriberController {

        static final Map<String, List<Recorded>> REQUESTS = new ConcurrentHashMap<>();

        record Recorded(String timestamp, String signature, String body) { }

        @RestController
        static class Handler {

            @PostMapping("/stub/subscriber/{key}")
            ResponseEntity<String> handle(@PathVariable String key, @RequestBody String body,
                                          jakarta.servlet.http.HttpServletRequest request) {
                REQUESTS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(new Recorded(request.getHeader("X-Schedular-Timestamp"),
                                request.getHeader("X-Schedular-Signature"), body));
                return ResponseEntity.ok("{\"received\":true}");
            }
        }
    }

    // ---------- 公共工具（复用既有测试模式） ----------

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

    private static String field(String body, String name) {
        try {
            return new ObjectMapper().readTree(body).path(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String registerAgent(String agentId, String resultMode, String endpoint) {
        assertThat(exchange("POST", "/api/agents", ADMIN_KEY, Map.of(
                "agent_id", agentId,
                "agent_version", "1.0.0",
                "trigger_type", "HTTP_API",
                "endpoint_url", endpoint,
                "result_mode", resultMode)).getStatusCode().is2xxSuccessful()).isTrue();
        return agentId;
    }

    private String createTask(String agentId, int timeoutSeconds, int retryCount) {
        ResponseEntity<String> resp = exchange("POST", "/api/tasks", CALLER_KEY, Map.of(
                "task", Map.of("name", "e2e验收-" + agentId,
                        "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", agentId, "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", timeoutSeconds, "retry_count", retryCount,
                        "max_concurrent_executions", 1)));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String taskId = field(resp.getBody(), "task_id");
        createdTaskIds.add(taskId);
        return taskId;
    }

    private void registerSubscription(String key, String taskId) {
        assertThat(exchange("POST", "/api/subscriptions", CALLER_KEY, Map.of(
                "callback_url", "http://localhost:" + port + "/stub/subscriber/" + key,
                "filter_task_ids", List.of(taskId))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode executionDetail(String executionId) {
        ResponseEntity<String> resp = exchange("GET", "/api/executions/" + executionId,
                CALLER_KEY, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return new ObjectMapper().readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<JsonNode> taskExecutions(String taskId) {
        ResponseEntity<String> resp = exchange("GET", "/api/tasks/" + taskId + "/executions",
                CALLER_KEY, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode node = new ObjectMapper().readTree(resp.getBody());
            List<JsonNode> list = new CopyOnWriteArrayList<>();
            node.forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 经缝一 GET /api/events 查询本租户事件。 */
    private List<JsonNode> apiEvents(String taskId, String eventType) {
        ResponseEntity<String> resp = exchange("GET",
                "/api/events?task_id=" + taskId + "&event_type=" + eventType + "&limit=200",
                CALLER_KEY, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode node = new ObjectMapper().readTree(resp.getBody());
            List<JsonNode> list = new CopyOnWriteArrayList<>();
            node.forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awaitExecutionStatus(String taskId, String expectedStatus) {
        try {
            return await().atMost(Duration.ofSeconds(20)).until(() ->
                    taskExecutions(taskId).stream()
                            .filter(e -> expectedStatus.equals(e.path("status").asText()))
                            .findFirst().orElse(null), e -> e != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("等待状态 " + expectedStatus + " 超时，当前: "
                    + taskExecutions(taskId), e);
        }
    }

    private void awaitRecorded(String key, int expectedCount) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(StubSubscriberController.REQUESTS.getOrDefault(key, List.of()))
                        .hasSizeGreaterThanOrEqualTo(expectedCount));
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void verifySignature(String secret, StubSubscriberController.Recorded r) {
        assertThat(r.timestamp()).isNotNull();
        long ts = Long.parseLong(r.timestamp());
        assertThat(Duration.between(Instant.ofEpochSecond(ts), Instant.now()).abs())
                .isLessThan(Duration.ofMinutes(5));
        String expected = "sha256=" + hmacSha256Hex(secret, ts + "." + r.body());
        assertThat(r.signature()).isEqualTo(expected);
        String tampered = "sha256=" + hmacSha256Hex(secret, ts + "." + r.body() + "x");
        assertThat(tampered).isNotEqualTo(r.signature());
    }

    @AfterEach
    void pauseCreatedTasks() {
        for (String taskId : createdTaskIds) {
            exchange("POST", "/api/tasks/" + taskId + "/pause", CALLER_KEY, null);
        }
    }

    // ---------- a. 主链路（SYNC） ----------

    @Test
    @Order(1)
    void 主链路SYNC触发到签名推送全链路() {
        registerAgent("agent_e2e_sync", "SYNC",
                "http://localhost:" + port + "/stub/agent/e2e-sync-ok");
        String taskId = createTask("agent_e2e_sync", 10, 2);
        registerSubscription("e2e-sync-sub", taskId);

        JsonNode exec = awaitExecutionStatus(taskId, "SUCCEEDED");
        String executionId = exec.path("execution_id").asText();
        assertThat(exec.path("attempt").asInt()).isEqualTo(1);
        assertThat(exec.path("config_version").asInt()).isEqualTo(1);
        assertThat(exec.path("trigger_type").asText()).isEqualTo("SCHEDULED");

        // GET /api/events 可查生命周期与业务事件，且都引用本 execution
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<JsonNode> succeeded = apiEvents(taskId, "EXECUTION_SUCCEEDED");
            List<JsonNode> business = apiEvents(taskId, "BUSINESS_EVENT");
            assertThat(succeeded).anyMatch(e ->
                    executionId.equals(e.path("execution_id").asText()));
            assertThat(business).anyMatch(e ->
                    executionId.equals(e.path("execution_id").asText())
                            && "M001 缺料风险".equals(e.path("payload").path("title").asText()));
        });

        // 订阅推送：EXECUTION_SUCCEEDED + BUSINESS_EVENT，HMAC 可验算
        awaitRecorded("e2e-sync-sub", 2);
        ResponseEntity<String> subs = exchange("GET", "/api/subscriptions", CALLER_KEY, null);
        // secret 不回显 → 验签 secret 只能在创建响应中取；此订阅用独立 secret 记录不可得，
        // 改为在一致性用例外的专用用例中创建订阅时记录 secret。此处断言收到两条且 event_type 正确。
        List<JsonNode> received = StubSubscriberController.REQUESTS.get("e2e-sync-sub")
                .stream().map(r -> {
                    try {
                        return new ObjectMapper().readTree(r.body());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        assertThat(received).anyMatch(n -> "EXECUTION_SUCCEEDED".equals(
                n.path("event_type").asText()) && executionId.equals(
                n.path("execution_id").asText()));
        assertThat(received).anyMatch(n -> "BUSINESS_EVENT".equals(
                n.path("event_type").asText()));

        // 执行记录含版本引用
        JsonNode detail = executionDetail(executionId);
        assertThat(detail.path("config_version").asInt()).isEqualTo(1);
        assertThat(detail.path("result").path("status").asText()).isEqualTo("SUCCEEDED");
    }

    /** 创建订阅并返回 secret（创建响应一次性返回），用于 HMAC 验算用例。 */
    private String registerSubscriptionCaptureSecret(String key, String taskId) {
        ResponseEntity<String> resp = exchange("POST", "/api/subscriptions", CALLER_KEY, Map.of(
                "callback_url", "http://localhost:" + port + "/stub/subscriber/" + key,
                "filter_task_ids", List.of(taskId)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return field(resp.getBody(), "secret");
    }

    @Test
    @Order(2)
    void 订阅推送HMAC验算与篡改识别() {
        registerAgent("agent_e2e_hmac", "SYNC",
                "http://localhost:" + port + "/stub/agent/e2e-sync-ok");
        String taskId = createTask("agent_e2e_hmac", 10, 2);
        String secret = registerSubscriptionCaptureSecret("e2e-hmac-sub", taskId);

        JsonNode exec = awaitExecutionStatus(taskId, "SUCCEEDED");
        // 到首个 SUCCEEDED 即暂停任务，固定推送来源，只留一轮在途投递
        exchange("POST", "/api/tasks/" + taskId + "/pause", CALLER_KEY, null);
        awaitRecorded("e2e-hmac-sub", 2);
        List<JsonNode> nodes = StubSubscriberController.REQUESTS.get("e2e-hmac-sub").stream()
                .map(r -> {
                    try {
                        return new ObjectMapper().readTree(r.body());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        assertThat(nodes).allMatch(n -> taskId.equals(n.path("task_id").asText()));
        StubSubscriberController.REQUESTS.get("e2e-hmac-sub")
                .forEach(r -> verifySignature(secret, r));
        // 暂停前来得及完成的执行可能不止一个：任取一个执行，其生命周期+业务事件成对出现
        String recordedExec = nodes.get(0).path("execution_id").asText();
        assertThat(nodes).anyMatch(n -> "EXECUTION_SUCCEEDED".equals(n.path("event_type").asText())
                && recordedExec.equals(n.path("execution_id").asText()));
        assertThat(nodes).anyMatch(n -> "BUSINESS_EVENT".equals(n.path("event_type").asText())
                && recordedExec.equals(n.path("execution_id").asText()));
        assertThat(taskExecutions(taskId)).anyMatch(e ->
                recordedExec.equals(e.path("execution_id").asText()));
    }

    // ---------- b. EVENT_CALLBACK 路径 ----------

    @Test
    @Order(3)
    void EVENT_CALLBACK路径ack回调后下游断言与主链路一致() {
        registerAgent("agent_e2e_cb", "EVENT_CALLBACK",
                "http://localhost:" + port + "/stub/agent/callback/e2e-cb");
        String taskId = createTask("agent_e2e_cb", 60, 2);
        registerSubscription("e2e-cb-sub", taskId);

        // 到点触发 → 受理回执 → RUNNING（触发链路真实运行）
        String callbackInfo = await().atMost(Duration.ofSeconds(20)).until(
                () -> CALLBACKS.get("e2e-cb"), v -> v != null);
        String[] parts = callbackInfo.split("\\|", 3);
        String executionId = parts[0];
        String endpoint = parts[1];
        String token = parts[2];
        assertThat(executionDetail(executionId).path("status").asText()).isEqualTo("RUNNING");

        // 结果回调 → 终态
        Map<String, Object> resultBody = Map.of(
                "execution_id", executionId,
                "status", "SUCCEEDED",
                "started_at", "2026-11-01T21:00:05+08:00",
                "finished_at", "2026-11-01T21:02:11+08:00",
                "summary", "回调巡检完成",
                "business_events", List.of(Map.of(
                        "type", "INVENTORY_RISK", "severity", "HIGH",
                        "title", "M002 回调风险", "content", Map.of(),
                        "dedup_key", "M002-CB")),
                "report", Map.of());
        HttpHeaders cbHeaders = new HttpHeaders();
        cbHeaders.set("X-Callback-Token", token);
        cbHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> cb = rest.exchange("http://localhost:" + port + endpoint,
                HttpMethod.POST, new HttpEntity<>(resultBody, cbHeaders), String.class);
        assertThat(cb.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(executionDetail(executionId).path("status").asText()).isEqualTo("SUCCEEDED");

        // 下游断言与主链路一致：GET /api/events 可查、订阅方收到推送
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(apiEvents(taskId, "EXECUTION_SUCCEEDED"))
                    .anyMatch(e -> executionId.equals(e.path("execution_id").asText()));
            assertThat(apiEvents(taskId, "BUSINESS_EVENT"))
                    .anyMatch(e -> executionId.equals(e.path("execution_id").asText()));
        });
        awaitRecorded("e2e-cb-sub", 2);
        JsonNode detail = executionDetail(executionId);
        assertThat(detail.path("config_version").asInt()).isEqualTo(1);
        assertThat(detail.path("result").path("status").asText()).isEqualTo("SUCCEEDED");
    }

    // ---------- c. 失败升级 ----------

    @Test
    @Order(4)
    void 连续失败3次ESCALATED推送到订阅方() {
        STUB_COUNTERS.put("e2e-always-fail", new AtomicInteger());
        registerAgent("agent_e2e_fail", "SYNC",
                "http://localhost:" + port + "/stub/agent/e2e-always-fail");
        String taskId = createTask("agent_e2e_fail", 10, 0);
        registerSubscription("e2e-esc-sub", taskId);

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(apiEvents(taskId, "ESCALATED")).isNotEmpty());
        assertThat(apiEvents(taskId, "ESCALATED").get(0).path("payload").toString())
                .contains("\"consecutive_failures\":3");
        String lastExecId = jdbc.queryForObject(
                "SELECT execution_id FROM executions WHERE task_id = ? ORDER BY rowid DESC LIMIT 1",
                String.class, taskId);
        assertThat(apiEvents(taskId, "ESCALATED").get(0).path("execution_id").asText())
                .isEqualTo(lastExecId);

        awaitRecorded("e2e-esc-sub", 1);
        assertThat(StubSubscriberController.REQUESTS.get("e2e-esc-sub"))
                .anyMatch(r -> r.body().contains("ESCALATED") && r.body().contains(taskId));
        // 任务不自动暂停
        assertThat(field(exchange("GET", "/api/tasks/" + taskId, CALLER_KEY, null).getBody(),
                "status")).isEqualTo("ACTIVE");
    }

    // ---------- e. 版本化穿插 ----------

    @Test
    @Order(5)
    void 执行v1后PUT更新再执行引用v2() {
        registerAgent("agent_e2e_ver", "SYNC",
                "http://localhost:" + port + "/stub/agent/e2e-sync-ok");
        String taskId = createTask("agent_e2e_ver", 10, 2);

        JsonNode v1 = awaitExecutionStatus(taskId, "SUCCEEDED");
        assertThat(v1.path("config_version").asInt()).isEqualTo(1);

        // PUT 全量六块（与创建同构），产生 v2
        ResponseEntity<String> put = exchange("PUT", "/api/tasks/" + taskId, CALLER_KEY, Map.of(
                "task", Map.of("name", "e2e验收-版本化后",
                        "task_type", "AGENT_SCHEDULED_EXECUTION"),
                "target", Map.of("agent_id", "agent_e2e_ver", "trigger_type", "HTTP_API"),
                "schedule", Map.of("type", "CRON", "expression", CRON_EVERY_SECOND,
                        "timezone", "Asia/Shanghai"),
                "execution", Map.of("instruction", "检查库存风险v2"),
                "security", Map.of("tenant_id", "tenant-dev", "owner_user_id", "owner-1"),
                "execution_policy", Map.of(
                        "timeout_seconds", 10, "retry_count", 2,
                        "max_concurrent_executions", 1)));
        assertThat(put.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(put.getBody()).contains("\"current_version\":2");

        JsonNode v2 = await().atMost(Duration.ofSeconds(20)).until(() ->
                taskExecutions(taskId).stream()
                        .filter(e -> e.path("config_version").asInt() == 2
                                && "SUCCEEDED".equals(e.path("status").asText()))
                        .findFirst().orElse(null), e -> e != null);
        assertThat(v2.path("config_version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_config_versions WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(2);
    }

    // ---------- 数据一致性检查（#12 验收工具，唯一允许 JDBC 直查断言） ----------

    /**
     * 全量不变量检查（作用于本测试 JVM 共享 SQLite 库的全部数据，含既有用例与 E2E 产出）：
     * 1 执行版本引用、2 状态枚举、3 终态必有结果或错误、4 attempt>=1、
     * 5 事件→执行外键、6 事件类型与执行状态一致、7 投递无孤儿、8 连续失败计数与执行重放一致。
     */
    @Test
    @Order(10)
    void 数据一致性检查() {
        // 共享内存库中既有 context 的每秒任务仍在写入：先全部暂停并留出在途执行落库窗口，
        // 避免检查与终态/事件写入竞态（与 EscalationSemanticsTest 同款静默手法）
        jdbc.update("UPDATE tasks SET status = 'PAUSED' WHERE status = 'ACTIVE'");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int invariants = 8;

        // 1. executions.config_version ∈ 该任务 task_config_versions
        Integer badVersion = jdbc.queryForObject("""
                SELECT COUNT(*) FROM executions e
                WHERE NOT EXISTS (SELECT 1 FROM task_config_versions v
                                  WHERE v.task_id = e.task_id AND v.version = e.config_version)
                """, Integer.class);
        assertThat(badVersion).as("不变量1: 执行版本引用必须存在于任务配置版本").isZero();

        // 2. status 枚举合法
        Integer badStatus = jdbc.queryForObject("""
                SELECT COUNT(*) FROM executions WHERE status NOT IN
                ('PENDING','RUNNING','SUCCEEDED','FAILED','TIMEOUT')
                """, Integer.class);
        assertThat(badStatus).as("不变量2: 执行状态必须落在合法枚举内").isZero();

        // 3. 终态行必有 result 或 last_error
        Integer terminalNoTrace = jdbc.queryForObject("""
                SELECT COUNT(*) FROM executions WHERE status IN ('SUCCEEDED','FAILED','TIMEOUT')
                AND result IS NULL AND last_error IS NULL
                """, Integer.class);
        assertThat(terminalNoTrace).as("不变量3: 终态执行必须留有 result 或 last_error").isZero();

        // 4. attempt >= 1
        Integer badAttempt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE attempt < 1", Integer.class);
        assertThat(badAttempt).as("不变量4: attempt 必须 >= 1").isZero();

        // 5. BUSINESS_EVENT / EXECUTION_* 的 execution_id 必有对应 execution 行
        Integer orphanEventExec = jdbc.queryForObject("""
                SELECT COUNT(*) FROM events
                WHERE execution_id IS NOT NULL
                  AND (event_type = 'BUSINESS_EVENT' OR event_type LIKE 'EXECUTION_%')
                  AND NOT EXISTS (SELECT 1 FROM executions e
                                  WHERE e.execution_id = events.execution_id)
                """, Integer.class);
        assertThat(orphanEventExec).as("不变量5: 事件引用的执行必须存在").isZero();

        // 6. 事件类型与执行状态一致（终态执行必有对应终态事件；事件反向指向匹配状态的执行）
        Integer execMissingEvent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM executions e WHERE e.status = 'SUCCEEDED'
                AND NOT EXISTS (SELECT 1 FROM events ev WHERE ev.execution_id = e.execution_id
                                AND ev.event_type = 'EXECUTION_SUCCEEDED')
                """, Integer.class);
        assertThat(execMissingEvent).as("不变量6a: SUCCEEDED 执行必有 EXECUTION_SUCCEEDED 事件").isZero();
        Integer eventStatusMismatch = jdbc.queryForObject("""
                SELECT COUNT(*) FROM events ev JOIN executions e ON e.execution_id = ev.execution_id
                WHERE (ev.event_type = 'EXECUTION_SUCCEEDED' AND e.status <> 'SUCCEEDED')
                   OR (ev.event_type = 'EXECUTION_FAILED' AND e.status <> 'FAILED')
                   OR (ev.event_type = 'EXECUTION_TIMEOUT' AND e.status <> 'TIMEOUT')
                """, Integer.class);
        assertThat(eventStatusMismatch).as("不变量6b: 事件类型必须与执行状态一致").isZero();

        // 7. event_deliveries 的 event_id / subscription_id 无孤儿
        Integer orphanDelivery = jdbc.queryForObject("""
                SELECT COUNT(*) FROM event_deliveries d
                WHERE NOT EXISTS (SELECT 1 FROM events e WHERE e.event_id = d.event_id)
                   OR NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.id = d.subscription_id)
                """, Integer.class);
        assertThat(orphanDelivery).as("不变量7: 投递记录不得存在孤儿引用").isZero();

        // 8. tasks.consecutive_failures 与执行重放一致（自最后一次成功起的终态失败数）
        List<Map<String, Object>> mismatch = jdbc.queryForList("""
                SELECT t.task_id, t.consecutive_failures, replay.expected
                FROM tasks t
                JOIN (SELECT task_id,
                             SUM(CASE WHEN status IN ('FAILED','TIMEOUT')
                                      AND NOT EXISTS (SELECT 1 FROM executions later
                                                      WHERE later.task_id = e.task_id
                                                      AND later.status = 'SUCCEEDED'
                                                      AND later.rowid > e.rowid)
                                      THEN 1 ELSE 0 END) AS expected
                      FROM executions e GROUP BY task_id) replay
                  ON replay.task_id = t.task_id
                WHERE t.consecutive_failures <> replay.expected
                """);
        assertThat(mismatch).as("不变量8: 连续失败计数必须与失败执行重放一致: %s", mismatch).isEmpty();

        System.out.println("数据一致性检查：全绿（" + invariants + " 项不变量）");
        // ASCII 标记供 scripts/verify.sh 可靠 grep（Windows JVM stdout 可能为 GBK 编码）
        System.out.println("DATA_CONSISTENCY_CHECK_OK: " + invariants + " invariants green");
        org.slf4j.LoggerFactory.getLogger(EndToEndAcceptanceTest.class)
                .info("数据一致性检查：全绿（{} 项不变量）", invariants);
    }
}
