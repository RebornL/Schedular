package com.schedular.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.schedular.events.EventService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单 #7 订阅与事件推送：过滤语义、HMAC 签名可验算且篡改可识别、secret 一次性、
 * 生命周期+BUSINESS_EVENT 都推、失败重试后成功 / 穷尽 EXHAUSTED、CALLER 正常 CRUD。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "schedular.push.retry-delays=100ms,200ms,400ms")
@Import(SubscriptionPushSemanticsTest.StubSubscriberController.Handler.class)
class SubscriptionPushSemanticsTest {

    private static final String CALLER_A = "push-caller-a";
    private static final String CALLER_B = "push-caller-b";
    private static final String CALLER_C = "push-caller-c";
    private static final String CALLER_D = "push-caller-d";
    private static final String CALLER_E = "push-caller-e";
    private static final String CALLER_OTHER = "push-caller-other";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EventService eventService;

    @BeforeEach
    void seedCallers() {
        // 每个用例独立租户，避免未过滤订阅串扰；幂等播种
        seedCaller(jdbc, "tenant-push-a", CALLER_A);
        seedCaller(jdbc, "tenant-push-b", CALLER_B);
        seedCaller(jdbc, "tenant-push-c", CALLER_C);
        seedCaller(jdbc, "tenant-push-d", CALLER_D);
        seedCaller(jdbc, "tenant-push-e", CALLER_E);
        seedCaller(jdbc, "tenant-push-other", CALLER_OTHER);
    }

    private static void seedCaller(JdbcTemplate jdbc, String tenantId, String key) {
        String hash = HexFormat.of().formatHex(sha256(key));
        jdbc.update("""
                INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at)
                VALUES (?, ?, 'CALLER', ?, 1, '2026-01-01T00:00:00+08:00')
                ON CONFLICT(key_hash) DO NOTHING""", hash, "caller-" + tenantId, tenantId);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String subscriberUrl(String key) {
        return "http://localhost:" + port + "/stub/subscriber/" + key;
    }

    @LocalServerPort
    int port;

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

    private JsonNode read(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 创建订阅，返回 (subscription_id, secret)。 */
    private JsonNode createSubscription(String apiKey, Map<String, Object> body) {
        ResponseEntity<String> resp = exchange("POST", "/api/subscriptions", apiKey, body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return read(resp.getBody());
    }

    private com.schedular.storage.Event publish(String eventType, String tenantId, String taskId,
                                                String executionId, String severity,
                                                Map<String, Object> payload) {
        return eventService.persist(eventType, tenantId, taskId, executionId, severity, payload);
    }

    private void awaitDelivered(String eventId, int expectedAttempts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_deliveries WHERE event_id = ? AND status = 'DELIVERED' AND attempts = ?",
                    Integer.class, eventId, expectedAttempts);
            assertThat(n).isEqualTo(1);
        });
    }

    private void awaitStatus(String eventId, String status, int expectedAttempts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_deliveries WHERE event_id = ? AND status = ? AND attempts = ?",
                    Integer.class, eventId, status, expectedAttempts);
            assertThat(n).isEqualTo(1);
        });
    }

    private void awaitRecorded(String key, int expectedCount) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(StubSubscriberController.REQUESTS.getOrDefault(key, List.of()))
                        .hasSize(expectedCount));
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
        // 篡改 body 后原签名不再匹配
        String tampered = "sha256=" + hmacSha256Hex(secret, ts + "." + r.body() + "x");
        assertThat(tampered).isNotEqualTo(r.signature());
    }

    // ---------- CRUD 与 secret 一次性 ----------

    @Test
    void 订阅CRUD正常且secret仅创建响应返回一次() {
        ResponseEntity<String> created = exchange("POST", "/api/subscriptions", CALLER_A,
                Map.of("callback_url", "http://localhost:9/x", "filter_task_ids", List.of("T1"),
                        "min_severity", "HIGH", "dedup_window_seconds", 60));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        JsonNode node = read(created.getBody());
        assertThat(node.path("secret").asText()).matches("[0-9a-f]{64}");
        long id = node.path("subscription_id").asLong();

        // 列表不回显 secret
        ResponseEntity<String> list = exchange("GET", "/api/subscriptions", CALLER_A, null);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).doesNotContain("secret").contains("http://localhost:9/x");

        // 非法 callback_url / min_severity → 400
        assertThat(exchange("POST", "/api/subscriptions", CALLER_A,
                Map.of("callback_url", "ftp://x")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("POST", "/api/subscriptions", CALLER_A,
                Map.of("callback_url", "http://ok", "min_severity", "ULTRA"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // DELETE 停用（enabled=0，无物理删除）
        ResponseEntity<String> deleted = exchange("DELETE", "/api/subscriptions/" + id, CALLER_A, null);
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        assertThat(deleted.getBody()).contains("\"enabled\":false");
        assertThat(jdbc.queryForObject("SELECT enabled FROM subscriptions WHERE id = ?",
                Integer.class, id)).isZero();
        assertThat(exchange("GET", "/api/subscriptions", CALLER_A, null).getBody())
                .contains("http://localhost:9/x");

        // 跨租户 → 404 不泄露存在性
        assertThat(exchange("DELETE", "/api/subscriptions/" + id, CALLER_OTHER, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- 过滤语义 ----------

    @Test
    void task与事件类型过滤不匹配不推送() {
        createSubscription(CALLER_B, Map.of(
                "callback_url", subscriberUrl("filtered"),
                "filter_task_ids", List.of("T-A1"),
                "filter_event_types", List.of("EXECUTION_SUCCEEDED")));

        String matched = publish("EXECUTION_SUCCEEDED", "tenant-push-b", "T-A1",
                "E-A1", null, Map.of("summary", "命中", "attempt", 1)).eventId();
        String wrongTask = publish("EXECUTION_SUCCEEDED", "tenant-push-b", "T-A2",
                "E-A2", null, Map.of("summary", "任务不匹配", "attempt", 1)).eventId();
        String wrongType = publish("EXECUTION_FAILED", "tenant-push-b", "T-A1",
                "E-A3", null, Map.of("summary", "类型不匹配", "attempt", 1)).eventId();

        awaitStatus(matched, "DELIVERED", 1);
        awaitRecorded("filtered", 1);
        // 留出重试窗口后确认未命中事件没有任何投递行
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_deliveries WHERE event_id IN (?, ?)",
                Integer.class, wrongTask, wrongType)).isZero();
        assertThat(StubSubscriberController.REQUESTS.getOrDefault("filtered", List.of()))
                .hasSize(1);
    }

    @Test
    void minSeverity仅作用于业务事件且生命周期事件一律放行() {
        createSubscription(CALLER_C, Map.of(
                "callback_url", subscriberUrl("severity"),
                "min_severity", "HIGH"));

        String lifecycle = publish("EXECUTION_SUCCEEDED", "tenant-push-c", "T-C1",
                "E-C1", null, Map.of("summary", "生命周期无severity放行", "attempt", 1)).eventId();
        String low = publish("BUSINESS_EVENT", "tenant-push-c", "T-C1",
                "E-C2", "LOW", Map.of("type", "RISK", "title", "低危不推")).eventId();
        String high = publish("BUSINESS_EVENT", "tenant-push-c", "T-C1",
                "E-C3", "HIGH", Map.of("type", "RISK", "title", "高危推送")).eventId();

        awaitStatus(lifecycle, "DELIVERED", 1);
        awaitStatus(high, "DELIVERED", 1);
        awaitRecorded("severity", 2);
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_deliveries WHERE event_id = ?",
                Integer.class, low)).isZero();
    }

    // ---------- HMAC 与全类型推送 ----------

    @Test
    void 生命周期与业务事件都推送且签名可验算篡改可识别() {
        JsonNode sub = createSubscription(CALLER_D, Map.of(
                "callback_url", subscriberUrl("hmac")));
        String secret = sub.path("secret").asText();

        String lifecycle = publish("EXECUTION_SUCCEEDED", "tenant-push-d", "T-D1",
                "E-D1", null, Map.of("summary", "生命周期", "attempt", 1)).eventId();
        String business = publish("BUSINESS_EVENT", "tenant-push-d", "T-D1",
                "E-D2", "CRITICAL", Map.of("type", "RISK", "title", "业务事件", "content", Map.of())).eventId();

        awaitStatus(lifecycle, "DELIVERED", 1);
        awaitStatus(business, "DELIVERED", 1);
        awaitRecorded("hmac", 2);
        var recorded = StubSubscriberController.REQUESTS.get("hmac");
        recorded.forEach(r -> verifySignature(secret, r));
        // 信封字段齐全，payload 为对象；两事件异步推送，按 event_id 定位 BUSINESS_EVENT，不按下标
        JsonNode envelope = recorded.stream()
                .map(r -> read(r.body()))
                .filter(b -> business.equals(b.path("event_id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 BUSINESS_EVENT 推送: " + business));
        assertThat(envelope.path("event_type").asText()).isEqualTo("BUSINESS_EVENT");
        assertThat(envelope.path("tenant_id").asText()).isEqualTo("tenant-push-d");
        assertThat(envelope.path("payload").path("title").asText()).isEqualTo("业务事件");
    }

    // ---------- 失败重试 ----------

    @Test
    void 推送先5xx重试后200转DELIVERED且attempts推进() {
        String key = "flaky";
        StubSubscriberController.REMAINING_FAILURES.put(key, new AtomicInteger(2));
        JsonNode sub = createSubscription(CALLER_E, Map.of("callback_url", subscriberUrl(key)));
        String secret = sub.path("secret").asText();

        String eventId = publish("EXECUTION_SUCCEEDED", "tenant-push-e", "T-E1",
                "E-E1", null, Map.of("summary", "重试场景", "attempt", 1)).eventId();

        awaitStatus(eventId, "DELIVERED", 3);
        awaitRecorded(key, 3);
        Integer lastCode = jdbc.queryForObject(
                "SELECT last_status_code FROM event_deliveries WHERE event_id = ?",
                Integer.class, eventId);
        assertThat(lastCode).isEqualTo(200);
        StubSubscriberController.REQUESTS.get(key).forEach(r -> verifySignature(secret, r));
    }

    @Test
    void 重试穷尽转EXHAUSTED() {
        String key = "dead";
        StubSubscriberController.REMAINING_FAILURES.put(key, new AtomicInteger(99));
        createSubscription(CALLER_E, Map.of("callback_url", subscriberUrl(key)));

        String eventId = publish("EXECUTION_FAILED", "tenant-push-e", "T-E2",
                "E-E2", null, Map.of("summary", "穷尽场景", "attempt", 1)).eventId();

        awaitStatus(eventId, "EXHAUSTED", 4);
        awaitRecorded(key, 4);
    }

    // ---------- stub 订阅方 ----------

    static class StubSubscriberController {

        static final Map<String, List<Recorded>> REQUESTS = new ConcurrentHashMap<>();
        static final Map<String, AtomicInteger> REMAINING_FAILURES = new ConcurrentHashMap<>();

        record Recorded(String timestamp, String signature, String body) { }

        @org.springframework.web.bind.annotation.RestController
        static class Handler {

            @PostMapping("/stub/subscriber/{key}")
            ResponseEntity<String> handle(@PathVariable String key, @RequestBody String body,
                                          jakarta.servlet.http.HttpServletRequest request) {
                REQUESTS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(new Recorded(request.getHeader("X-Schedular-Timestamp"),
                                request.getHeader("X-Schedular-Signature"), body));
                AtomicInteger remaining = REMAINING_FAILURES.get(key);
                if (remaining != null && remaining.getAndDecrement() > 0) {
                    return ResponseEntity.status(500).body("{\"code\":\"SUBSCRIBER_ERROR\"}");
                }
                return ResponseEntity.ok("{\"received\":true}");
            }
        }
    }
}
