package com.schedular.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schedular.security.HmacSigner;
import com.schedular.storage.Event;
import com.schedular.storage.EventDelivery;
import com.schedular.storage.EventDeliveryRepository;
import com.schedular.storage.Subscription;
import com.schedular.storage.SubscriptionRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * EventSink 真实实现：按订阅过滤匹配 → 每订阅建 PENDING 投递行 → 异步 HMAC 推送，
 * 失败按 schedular.push.retry-delays 重试 3 次后 EXHAUSTED（docs/design/04）。
 * 尽力而为：publish 与推送异常一律吞掉记日志，不影响 EventService.persist。
 */
@Component
public class EventPusher implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventPusher.class);

    private static final String TYPE_BUSINESS_EVENT = "BUSINESS_EVENT";
    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final SubscriptionRepository subscriptionRepository;
    private final EventDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;
    private final List<Duration> retryDelays;
    private final HttpClient http = HttpClient.newHttpClient();

    public EventPusher(SubscriptionRepository subscriptionRepository,
                       EventDeliveryRepository deliveryRepository,
                       ObjectMapper objectMapper, Clock clock,
                       ThreadPoolTaskScheduler pushTaskScheduler,
                       @Value("${schedular.push.retry-delays}") List<Duration> retryDelays) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduler = pushTaskScheduler;
        this.retryDelays = retryDelays;
    }

    @Override
    public void publish(Event event) {
        try {
            for (Subscription subscription : subscriptionRepository.findByTenant(event.tenantId())) {
                if (!subscription.enabled() || !matches(subscription, event)) {
                    continue;
                }
                if (isDuplicate(subscription, event)) {
                    // 窗口内同 dedup_key 已 DELIVERED：不推送，落 DEDUPED 投递行留痕
                    deliveryRepository.insert(new EventDelivery(
                            event.eventId(), subscription.id().intValue(), "DEDUPED", 0,
                            null, null, null, "窗口内同 dedup_key 已推送", nowIso()));
                    continue;
                }
                EventDelivery delivery = new EventDelivery(
                        event.eventId(), subscription.id().intValue(), "PENDING", 0,
                        null, null, null, null, nowIso());
                deliveryRepository.insert(delivery);
                scheduler.execute(() -> attempt(subscription, event));
            }
        } catch (RuntimeException e) {
            log.warn("订阅匹配/投递行创建失败: event_id={}", event.eventId(), e);
        }
    }

    /**
     * 投递前重查投递行：已非 PENDING（DEDUPED/终态/他方处理中）即放弃，防 sweeper 与内存重试双发。
     * 失败路径（非 2xx / 连接异常）统一走 recordFailure：单次更新推进 PENDING 或 EXHAUSTED。
     */
    void attempt(Subscription subscription, Event event) {
        EventDelivery delivery;
        try {
            delivery = deliveryRepository
                    .find(event.eventId(), subscription.id().intValue()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("投递行读取失败: event_id={}, subscription_id={}",
                    event.eventId(), subscription.id(), e);
            return;
        }
        if (delivery == null || !"PENDING".equals(delivery.status())) {
            return;
        }
        int attempts = delivery.attempts() + 1;
        Integer statusCode = null;
        String error = "连接异常";
        try {
            statusCode = push(subscription, envelope(event));
            error = "HTTP " + statusCode;
        } catch (RuntimeException | IOException e) {
            log.warn("推送异常: event_id={}, subscription_id={}",
                    event.eventId(), subscription.id(), e);
        }
        if (statusCode != null && statusCode >= 200 && statusCode < 300) {
            deliveryRepository.update(new EventDelivery(
                    delivery.eventId(), delivery.subscriptionId(), "DELIVERED",
                    attempts, null, nowIso(), statusCode, null, delivery.createdAt()));
            return;
        }
        recordFailure(subscription, event, delivery, attempts, statusCode, error);
    }

    /** 失败推进：未穷尽 → PENDING + next_retry_at 并安排重试；穷尽 → EXHAUSTED 终态，一次更新完成。 */
    private void recordFailure(Subscription subscription, Event event, EventDelivery delivery,
                               int attempts, Integer statusCode, String error) {
        try {
            boolean exhausted = attempts > retryDelays.size();
            deliveryRepository.update(new EventDelivery(
                    delivery.eventId(), delivery.subscriptionId(),
                    exhausted ? "EXHAUSTED" : "PENDING",
                    attempts,
                    exhausted ? null : nowIsoAfter(retryDelays.get(attempts - 1)),
                    null, statusCode, error, delivery.createdAt()));
            if (!exhausted) {
                scheduler.schedule(() -> attempt(subscription, event),
                        Instant.now(clock).plus(retryDelays.get(attempts - 1)));
            }
        } catch (RuntimeException e) {
            log.warn("投递行更新失败: event_id={}, subscription_id={}",
                    event.eventId(), subscription.id(), e);
        }
    }

    /** 去重：仅 payload 带 dedup_key 且订阅配置了窗口时生效；key = 订阅 id + payload.dedup_key。 */
    private boolean isDuplicate(Subscription subscription, Event event) {
        Integer window = subscription.dedupWindowSeconds();
        if (window == null || window <= 0) {
            return false;
        }
        JsonNode payload = parse(objectMapper, event.payload());
        if (payload == null || !payload.hasNonNull("dedup_key")
                || payload.get("dedup_key").asText().isBlank()) {
            return false;
        }
        String dedupKey = payload.get("dedup_key").asText();
        // shortcut: occurred_at 与 since 按同格式 ISO 字符串做字典序比较，跨亚秒精度边界可能偏差，同源写入下可接受
        String since = OffsetDateTime.ofInstant(Instant.now(clock).minusSeconds(window), clock.getZone()).toString();
        for (String delivered : deliveryRepository
                .findDeliveredPayloadsSince(subscription.id().intValue(), since)) {
            JsonNode p = parse(objectMapper, delivered);
            if (p != null && dedupKey.equals(p.path("dedup_key").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private int push(Subscription subscription, String body) throws IOException {
        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = HmacSigner.hmacSha256Hex(subscription.secret(),
                timestamp + "." + body);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.callbackUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Schedular-Timestamp", String.valueOf(timestamp))
                    .header("X-Schedular-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("callback_url 非法: " + subscription.callbackUrl(), e);
        }
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("推送被中断", e);
        }
    }

    boolean matches(Subscription subscription, Event event) {
        if (!filterPasses(subscription.filterTaskIds(), event.taskId())
                || !filterPasses(subscription.filterEventTypes(), event.eventType())) {
            return false;
        }
        if (TYPE_BUSINESS_EVENT.equals(event.eventType())
                && subscription.minSeverity() != null) {
            Integer min = severityRank(subscription.minSeverity());
            Integer actual = severityRank(event.severity());
            // min_severity 仅作用于 BUSINESS_EVENT；severity 缺失视为不达标
            return min == null || (actual != null && actual >= min);
        }
        return true;
    }

    private boolean filterPasses(String filterJson, String value) {
        if (filterJson == null || filterJson.isBlank()) {
            return true;
        }
        JsonNode array = parse(objectMapper, filterJson);
        if (array == null || !array.isArray() || array.isEmpty()) {
            return true;
        }
        for (JsonNode item : array) {
            if (item.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Integer severityRank(String severity) {
        if (severity == null) {
            return null;
        }
        int index = SEVERITIES.indexOf(severity);
        return index < 0 ? null : index;
    }

    /** 事件信封：events 表行同构，payload 以 JSON 对象嵌入（docs/design/03）。 */
    private String envelope(Event event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event_id", event.eventId());
        node.put("event_type", event.eventType());
        node.put("tenant_id", event.tenantId());
        node.put("task_id", event.taskId());
        node.put("execution_id", event.executionId());
        node.put("severity", event.severity());
        node.put("occurred_at", event.occurredAt());
        JsonNode payload = parse(objectMapper, event.payload());
        node.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件信封序列化失败", e);
        }
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }

    private String nowIsoAfter(Duration delay) {
        return OffsetDateTime.ofInstant(Instant.now(clock).plus(delay), clock.getZone()).toString();
    }
}
