package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** events 表唯一入口；只追加，无更新无删除。 */
@Repository
public class EventRepository {

    private static final RowMapper<Event> MAPPER = (rs, i) -> new Event(
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("tenant_id"),
            rs.getString("task_id"),
            rs.getString("execution_id"),
            rs.getString("severity"),
            rs.getString("occurred_at"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Event event) {
        jdbc.update("""
                INSERT INTO events (event_id, event_type, tenant_id, task_id, execution_id,
                                    severity, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                event.eventId(), event.eventType(), event.tenantId(), event.taskId(),
                event.executionId(), event.severity(), event.occurredAt(), event.payload());
    }

    public List<Event> findByTenant(String tenantId) {
        return jdbc.query("SELECT * FROM events WHERE tenant_id = ? ORDER BY occurred_at DESC",
                MAPPER, tenantId);
    }

    public List<Event> findByTaskId(String tenantId, String taskId) {
        return jdbc.query(
                "SELECT * FROM events WHERE tenant_id = ? AND task_id = ? ORDER BY occurred_at DESC",
                MAPPER, tenantId, taskId);
    }

    public List<Event> findByExecutionId(String executionId) {
        return jdbc.query("SELECT * FROM events WHERE execution_id = ? ORDER BY occurred_at ASC, id ASC",
                MAPPER, executionId);
    }

    public Optional<Event> findById(String eventId) {
        return jdbc.query("SELECT * FROM events WHERE event_id = ?", MAPPER, eventId).stream().findFirst();
    }

    /**
     * GET /api/events 兜底查询：租户强制过滤 + 可选 task_id / event_type / since / limit。
     * min_severity 语义与订阅匹配一致：仅约束 BUSINESS_EVENT（生命周期事件一律放行），rank 见 03。
     */
    public List<Event> search(String tenantId, String taskId, String eventType,
                              Integer minSeverityRank, String sinceIso, int limit) {
        return jdbc.query("""
                SELECT * FROM events WHERE tenant_id = ?
                  AND (? IS NULL OR task_id = ?)
                  AND (? IS NULL OR event_type = ?)
                  AND (? IS NULL OR julianday(occurred_at) >= julianday(?))
                  AND (? IS NULL OR event_type <> 'BUSINESS_EVENT'
                       OR (severity IS NOT NULL AND CASE severity
                            WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1
                            WHEN 'HIGH' THEN 2 WHEN 'CRITICAL' THEN 3 END >= ?))
                ORDER BY occurred_at DESC, event_id DESC LIMIT ?""",
                MAPPER, tenantId, taskId, taskId, eventType, eventType,
                sinceIso, sinceIso, minSeverityRank, minSeverityRank, limit);
    }
}
