package com.schedular.storage;

import java.util.List;
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
}
