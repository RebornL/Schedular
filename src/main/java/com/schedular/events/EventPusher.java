package com.schedular.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

    private void attempt(Subscription subscription, Event event) {
        try {
            EventDelivery delivery = deliveryRepository
                    .find(event.eventId(), subscription.id().intValue()).orElse(null);
            if (delivery == null || !"PENDING".equals(delivery.status())) {
                return;
            }
            String body = envelope(event);
            int statusCode = push(subscription, body);
            int attempts = delivery.attempts() + 1;
            if (statusCode >= 200 && statusCode < 300) {
                deliveryRepository.update(new EventDelivery(
                        delivery.eventId(), delivery.subscriptionId(), "DELIVERED",
                        attempts, null, nowIso(), statusCode, null, delivery.createdAt()));
                return;
            }
            deliveryRepository.update(new EventDelivery(
                    delivery.eventId(), delivery.subscriptionId(), "PENDING",
                    attempts,
                    attempts <= retryDelays.size() ? nowIsoAfter(retryDelays.get(attempts - 1)) : null,
                    null, statusCode,
                    "HTTP " + statusCode, delivery.createdAt()));
            if (attempts <= retryDelays.size()) {
                scheduler.schedule(() -> attempt(subscription, event), Instant.now(clock).plus(retryDelays.get(attempts - 1)));
            } else {
                deliveryRepository.update(new EventDelivery(
                        delivery.eventId(), delivery.subscriptionId(), "EXHAUSTED",
                        attempts, null, null, statusCode,
                        "重试穷尽: HTTP " + statusCode, delivery.createdAt()));
            }
        } catch (RuntimeException | IOException e) {
            log.warn("推送异常: event_id={}, subscription_id={}",
                    event.eventId(), subscription.id(), e);
            markFailedOnException(subscription, event);
        }
    }

    private void markFailedOnException(Subscription subscription, Event event) {
        try {
            deliveryRepository.find(event.eventId(), subscription.id().intValue()).ifPresent(delivery -> {
                if (!"PENDING".equals(delivery.status())) {
                    return;
                }
                int attempts = delivery.attempts() + 1;
                if (attempts <= retryDelays.size()) {
                    deliveryRepository.update(new EventDelivery(
                            delivery.eventId(), delivery.subscriptionId(), "PENDING", attempts,
                            nowIsoAfter(retryDelays.get(attempts - 1)), null, null,
                            "连接异常", delivery.createdAt()));
                    scheduler.schedule(() -> attempt(subscription, event),
                            Instant.now(clock).plus(retryDelays.get(attempts - 1)));
                } else {
                    deliveryRepository.update(new EventDelivery(
                            delivery.eventId(), delivery.subscriptionId(), "EXHAUSTED", attempts,
                            null, null, null, "连接异常", delivery.createdAt()));
                }
            });
        } catch (RuntimeException e) {
            log.warn("投递行更新失败: event_id={}", event.eventId(), e);
        }
    }

    private int push(Subscription subscription, String body) throws IOException {
        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = hmacSha256Hex(subscription.secret(),
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

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }

    private String nowIsoAfter(Duration delay) {
        return OffsetDateTime.ofInstant(Instant.now(clock).plus(delay), clock.getZone()).toString();
    }
}
