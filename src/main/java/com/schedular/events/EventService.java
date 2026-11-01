package com.schedular.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.storage.Event;
import com.schedular.storage.EventRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 事件落库 + 投递出口调用：产生即持久化，推送是投递层（#7）。
 * 未注册 EventSink 时默认 no-op。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final EventSink sink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventService(EventRepository eventRepository, ObjectProvider<EventSink> sinkProvider,
                        ObjectMapper objectMapper, Clock clock) {
        this.eventRepository = eventRepository;
        this.sink = sinkProvider.getIfAvailable(() -> event -> { });
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Event persist(String eventType, String tenantId, String taskId, String executionId,
                         String severity, Map<String, Object> payload) {
        Event event = new Event(
                "V" + UUID.randomUUID().toString().replace("-", ""),
                eventType, tenantId, taskId, executionId, severity,
                OffsetDateTime.now(clock).toString(), toJson(payload));
        eventRepository.insert(event);
        try {
            sink.publish(event);
        } catch (RuntimeException e) {
            // 投递出口故障不影响事件持久化；推送重试语义由 #7 实现
            log.warn("事件投递出口异常: event_id={}, type={}", event.eventId(), event.eventType(), e);
        }
        return event;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件 payload 序列化失败", e);
        }
    }
}
