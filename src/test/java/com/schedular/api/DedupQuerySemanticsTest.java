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
 * 工单 #8 推送可靠性与查询兜底：dedup_window_seconds 窗口去重（DEDUPED）、
 * DeliverySweeper 到期 PENDING 重新入队、重试穷尽后 GET /api/events 仍可获取、
 * 查询过滤（task/type/级别/since/limit）与租户隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 独立内存库：避免 100ms sweeper 与其他测试类（单连接池）争抢同名共享库连接
        "spring.datasource.url=jdbc:sqlite:file:schedular-d8-test?mode=memory&cache=shared",
        // 内存重试第一跳拉长到 2s，让 sweeper（100ms）成为唯一重试驱动，避免双发竞态
        "schedular.push.retry-delays=2s,2s,2m",
        "schedular.push.sweeper-interval=100ms"})
@Import(DedupQuerySemanticsTest.StubSubscriberController.Handler.class)
class DedupQuerySemanticsTest {

    private static final String CALLER_A = "d8-caller-a";
    private static final String CALLER_B = "d8-caller-b";
    private static final String CALLER_C = "d8-caller-c";
    private static final String CALLER_D = "d8-caller-d";
    private static final String CALLER_QUERY = "d8-caller-query";
    private static final String CALLER_QUERY2 = "d8-caller-query2";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EventService eventService;

    @LocalServerPort
    int port;

    @BeforeEach
    void seedCallers() {
        seedCaller(jdbc, "tenant-d8-a", CALLER_A);
        seedCaller(jdbc, "tenant-d8-b", CALLER_B);
        seedCaller(jdbc, "tenant-d8-c", CALLER_C);
        seedCaller(jdbc, "tenant-d8-d", CALLER_D);
        seedCaller(jdbc, "tenant-d8-query", CALLER_QUERY);
        seedCaller(jdbc, "tenant-d8-query2", CALLER_QUERY2);
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
        return "http://localhost:" + port + "/stub/d8/" + key;
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

    private JsonNode read(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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

    private void awaitStatus(String eventId, String status, Integer expectedAttempts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_deliveries WHERE event_id = ? AND status = ? "
                            + "AND (? IS NULL OR attempts = ?)",
                    Integer.class, eventId, status, expectedAttempts, expectedAttempts);
            assertThat(n).isEqualTo(1);
        });
    }

    private void awaitRecorded(String key, int expectedCount) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(StubSubscriberController.REQUESTS.getOrDefault(key, List.of()))
                        .hasSize(expectedCount));
    }

    /** 把投递行的 next_retry_at 拨到过去，让 sweeper 立即重新入队。 */
    private void rewindNextRetry(String eventId) {
        jdbc.update("UPDATE event_deliveries SET next_retry_at = '2000-01-01T00:00:00+08:00' "
                + "WHERE event_id = ? AND status = 'PENDING'", eventId);
    }

    private JsonNode getEvents(String apiKey, String query) {
        // 用完整 URI：String 形式会被 RestTemplate 把 % 二次编码（since 中的 %3A 会坏）
        ResponseEntity<String> resp = rest.exchange(
                java.net.URI.create(rest.getRootUri() + "/api/events" + (query == null ? "" : query)),
                HttpMethod.GET, new HttpEntity<>(headers(apiKey)), String.class);
        assertThat(resp.getStatusCode().value()).as("GET /api/events%s 响应: %s",
                query, resp.getBody()).isEqualTo(200);
        return read(resp.getBody());
    }

    // ---------- 去重 ----------

    @Test
    void 窗口内同dedup_key只推一次且投递行记DEDUPED() {
        createSubscription(CALLER_A, Map.of(
                "callback_url", subscriberUrl("dedup-win"),
                "dedup_window_seconds", 60));

        String first = publish("BUSINESS_EVENT", "tenant-d8-a", "T-D8A1", "E-D8A1", "HIGH",
                Map.of("type", "RISK", "title", "第一次", "dedup_key", "K-WIN")).eventId();
        awaitStatus(first, "DELIVERED", 1);
        awaitRecorded("dedup-win", 1);
        // 先等第一条 DELIVERED，再去重判定只针对已 DELIVERED 的同 key 事件
        String second = publish("BUSINESS_EVENT", "tenant-d8-a", "T-D8A1", "E-D8A2", "HIGH",
                Map.of("type", "RISK", "title", "第二次", "dedup_key", "K-WIN")).eventId();
        awaitStatus(second, "DEDUPED", 0);
        awaitRecorded("dedup-win", 1);
        Integer dedupAttempts = jdbc.queryForObject(
                "SELECT attempts FROM event_deliveries WHERE event_id = ?", Integer.class, second);
        assertThat(dedupAttempts).isZero();
    }

    @Test
    void 窗口过期后同dedup_key再推成功() throws InterruptedException {
        createSubscription(CALLER_B, Map.of(
                "callback_url", subscriberUrl("dedup-expire"),
                "dedup_window_seconds", 1));

        String first = publish("BUSINESS_EVENT", "tenant-d8-b", "T-D8B1", "E-D8B1", "HIGH",
                Map.of("type", "RISK", "title", "窗口内", "dedup_key", "K-EXPIRE")).eventId();
        awaitStatus(first, "DELIVERED", 1);
        Thread.sleep(1300);

        String second = publish("BUSINESS_EVENT", "tenant-d8-b", "T-D8B1", "E-D8B2", "HIGH",
                Map.of("type", "RISK", "title", "窗口外", "dedup_key", "K-EXPIRE")).eventId();
        awaitStatus(second, "DELIVERED", 1);
        awaitRecorded("dedup-expire", 2);
    }

    @Test
    void 无dedup_key不去重() {
        createSubscription(CALLER_C, Map.of(
                "callback_url", subscriberUrl("no-dedup"),
                "dedup_window_seconds", 60));

        String first = publish("BUSINESS_EVENT", "tenant-d8-c", "T-D8C1", "E-D8C1", "HIGH",
                Map.of("type", "RISK", "title", "无键一")).eventId();
        String second = publish("BUSINESS_EVENT", "tenant-d8-c", "T-D8C1", "E-D8C2", "HIGH",
                Map.of("type", "RISK", "title", "无键二")).eventId();

        awaitStatus(first, "DELIVERED", 1);
        awaitStatus(second, "DELIVERED", 1);
        awaitRecorded("no-dedup", 2);
        Integer pendingOrDeduped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_deliveries WHERE event_id IN (?, ?) "
                        + "AND status IN ('DEDUPED', 'EXHAUSTED')",
                Integer.class, first, second);
        assertThat(pendingOrDeduped).isZero();
    }

    // ---------- DeliverySweeper ----------

    @Test
    void sweeper将到期PENDING重新入队并成功投递() {
        StubSubscriberController.REMAINING_FAILURES.put("d8-sweep", new AtomicInteger(1));
        createSubscription(CALLER_D, Map.of("callback_url", subscriberUrl("d8-sweep")));

        String eventId = publish("EXECUTION_SUCCEEDED", "tenant-d8-d", "T-D8D1",
                "E-D8D1", null, Map.of("summary", "sweeper 恢复", "attempt", 1)).eventId();

        // 第一次推送 5xx：内存重试在 +2s，sweeper 不会提前扫到（next_retry_at 未到期）
        awaitStatus(eventId, "PENDING", 1);
        assertThat(StubSubscriberController.REQUESTS.getOrDefault("d8-sweep", List.of())).hasSize(1);

        // 订阅方恢复 + 拨回 next_retry_at → sweeper 重新入队推送成功
        StubSubscriberController.REMAINING_FAILURES.put("d8-sweep", new AtomicInteger(0));
        rewindNextRetry(eventId);
        awaitStatus(eventId, "DELIVERED", 2);
        awaitRecorded("d8-sweep", 2);
    }

    @Test
    void 重试穷尽EXHAUSTED后事件仍可从查询接口获取() {
        StubSubscriberController.REMAINING_FAILURES.put("d8-exhaust", new AtomicInteger(99));
        createSubscription(CALLER_D, Map.of("callback_url", subscriberUrl("d8-exhaust")));

        String eventId = publish("EXECUTION_FAILED", "tenant-d8-d", "T-D8D2",
                "E-D8D2", null, Map.of("summary", "穷尽后兜底", "attempt", 1)).eventId();
        awaitStatus(eventId, "PENDING", 1);

        // 逐轮拨回 next_retry_at，由 sweeper 驱动 4 次尝试后 EXHAUSTED
        rewindNextRetry(eventId);
        awaitStatus(eventId, "PENDING", 2);
        rewindNextRetry(eventId);
        awaitStatus(eventId, "PENDING", 3);
        rewindNextRetry(eventId);
        awaitStatus(eventId, "EXHAUSTED", 4);

        JsonNode events = getEvents(CALLER_QUERY, null);
        // 不同租户的 CALLER_QUERY 看不到；CALLER_D 租户才能查到
        assertThat(events.toString()).doesNotContain(eventId);
        JsonNode own = getEvents(CALLER_D, null);
        boolean found = false;
        for (JsonNode e : own) {
            if (eventId.equals(e.path("event_id").asText())) {
                found = true;
                assertThat(e.path("event_type").asText()).isEqualTo("EXECUTION_FAILED");
                assertThat(e.path("payload").path("summary").asText()).isEqualTo("穷尽后兜底");
            }
        }
        assertThat(found).as("EXHAUSTED 事件应可从查询接口获取: %s", own).isTrue();
    }

    // ---------- 查询过滤 ----------

    @Test
    void 事件查询按任务类型级别since与limit过滤且租户隔离() throws InterruptedException {
        com.schedular.storage.Event e1 = publish("EXECUTION_SUCCEEDED", "tenant-d8-query",
                "T-Q1", "E-Q1", null, Map.of("summary", "成功", "attempt", 1));
        com.schedular.storage.Event e2 = publish("BUSINESS_EVENT", "tenant-d8-query",
                "T-Q2", "E-Q2", "LOW", Map.of("type", "RISK", "title", "低危"));
        com.schedular.storage.Event e3 = publish("BUSINESS_EVENT", "tenant-d8-query",
                "T-Q1", "E-Q3", "HIGH", Map.of("type", "RISK", "title", "高危", "dedup_key", "K-Q"));
        Thread.sleep(20);
        // since（用 UTC 形式，避免 offset 的 + 在 URL 传输中被解码为空格）
        String since = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
        Thread.sleep(20);
        com.schedular.storage.Event e4 = publish("EXECUTION_FAILED", "tenant-d8-query",
                "T-Q1", "E-Q4", null, Map.of("summary", "失败", "attempt", 1));
        com.schedular.storage.Event other = publish("EXECUTION_SUCCEEDED", "tenant-d8-query2",
                "T-Q9", "E-Q9", null, Map.of("summary", "他租户", "attempt", 1));

        // 全量：仅本租户，occurred_at 倒序
        JsonNode all = getEvents(CALLER_QUERY, null);
        assertThat(all).hasSize(4);
        assertThat(all.toString()).doesNotContain(other.eventId());
        assertThat(all.get(0).path("event_id").asText()).isEqualTo(e4.eventId());

        // task_id：T-Q1 上有 e1 / e3 / e4
        JsonNode byTask = getEvents(CALLER_QUERY, "?task_id=T-Q1");
        assertThat(byTask).hasSize(3);
        assertThat(byTask.toString()).contains(e1.eventId()).contains(e3.eventId()).contains(e4.eventId());

        // event_type
        JsonNode byType = getEvents(CALLER_QUERY, "?event_type=BUSINESS_EVENT");
        assertThat(byType).hasSize(2);
        assertThat(byType.toString()).contains(e2.eventId()).contains(e3.eventId());

        // min_severity：仅约束 BUSINESS_EVENT，生命周期事件放行
        JsonNode bySeverity = getEvents(CALLER_QUERY, "?min_severity=HIGH");
        assertThat(bySeverity).hasSize(3);
        assertThat(bySeverity.toString()).contains(e1.eventId()).contains(e3.eventId()).contains(e4.eventId());

        // since（offset 含 +，必须 URL 编码）
        String encodedSince = java.net.URLEncoder.encode(since, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode sinceAfter = getEvents(CALLER_QUERY, "?since=" + encodedSince);
        assertThat(sinceAfter).hasSize(1);
        assertThat(sinceAfter.get(0).path("event_id").asText()).isEqualTo(e4.eventId());

        // limit
        assertThat(getEvents(CALLER_QUERY, "?limit=2")).hasSize(2);

        // 组合
        JsonNode combined = getEvents(CALLER_QUERY, "?task_id=T-Q1&event_type=BUSINESS_EVENT&min_severity=HIGH");
        assertThat(combined).hasSize(1);
        assertThat(combined.get(0).path("event_id").asText()).isEqualTo(e3.eventId());

        // 非法参数 → 400
        assertThat(exchange("GET", "/api/events?limit=0", CALLER_QUERY, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("GET", "/api/events?limit=201", CALLER_QUERY, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("GET", "/api/events?min_severity=ULTRA", CALLER_QUERY, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("GET", "/api/events?since=not-a-time", CALLER_QUERY, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // 信封字段齐全
        assertThat(all.get(0).has("event_id")).isTrue();
        assertThat(all.get(0).has("occurred_at")).isTrue();
        assertThat(all.get(0).path("payload").isObject()).isTrue();
    }

    // ---------- stub 订阅方 ----------

    static class StubSubscriberController {

        static final Map<String, List<Recorded>> REQUESTS = new ConcurrentHashMap<>();
        static final Map<String, AtomicInteger> REMAINING_FAILURES = new ConcurrentHashMap<>();

        record Recorded(String timestamp, String signature, String body) { }

        @RestController
        static class Handler {

            @PostMapping("/stub/d8/{key}")
            ResponseEntity<String> handle(@PathVariable String key, @RequestBody String body) {
                REQUESTS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(new Recorded(null, null, body));
                AtomicInteger remaining = REMAINING_FAILURES.get(key);
                if (remaining != null && remaining.getAndDecrement() > 0) {
                    return ResponseEntity.status(500).body("{\"code\":\"SUBSCRIBER_ERROR\"}");
                }
                return ResponseEntity.ok("{\"received\":true}");
            }
        }
    }
}
