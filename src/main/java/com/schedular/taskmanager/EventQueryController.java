package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schedular.common.ApiException;
import com.schedular.security.TenantContext;
import com.schedular.security.TenantPrincipal;
import com.schedular.storage.Event;
import com.schedular.storage.EventRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/events 事件兜底查询：按 task_id / event_type / min_severity / since / limit 过滤，
 * tenant 按 TenantContext 强制过滤，按 occurred_at 倒序返回信封数组（docs/design/03 信封同构）。
 */
@RestController
public class EventQueryController {

    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public EventQueryController(EventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/events")
    public List<ObjectNode> list(
            @RequestParam(name = "task_id", required = false) String taskId,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "min_severity", required = false) String minSeverity,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "50") int limit) {
        TenantPrincipal principal = TenantContext.get();
        if (principal == null || principal.tenantId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
                    "事件查询必须绑定租户：认证身份未绑定 tenant_id");
        }
        Integer minSeverityRank = null;
        if (minSeverity != null && !minSeverity.isBlank()) {
            minSeverityRank = SEVERITIES.indexOf(minSeverity);
            if (minSeverityRank < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MIN_SEVERITY",
                        "min_severity 仅支持 LOW / MEDIUM / HIGH / CRITICAL");
            }
        }
        String sinceIso = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceIso = OffsetDateTime.parse(since).toString();
            } catch (DateTimeParseException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SINCE",
                        "since 必须是合法的 ISO-8601 时间");
            }
        }
        if (limit < 1 || limit > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "limit 取值 1-200（默认 50）");
        }
        return eventRepository
                .search(principal.tenantId(), blankToNull(taskId), blankToNull(eventType),
                        minSeverityRank, sinceIso, limit)
                .stream()
                .map(this::envelope)
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 事件信封：events 表行同构，payload 以 JSON 对象嵌入。 */
    private ObjectNode envelope(Event event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event_id", event.eventId());
        node.put("event_type", event.eventType());
        node.put("tenant_id", event.tenantId());
        node.put("task_id", event.taskId());
        node.put("execution_id", event.executionId());
        node.put("severity", event.severity());
        node.put("occurred_at", event.occurredAt());
        JsonNode payload = parsePayload(event.payload());
        node.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        return node;
    }

    private JsonNode parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
