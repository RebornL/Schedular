package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** event_deliveries 表唯一入口；状态只前进（PENDING → DELIVERED / EXHAUSTED），无删除。 */
@Repository
public class EventDeliveryRepository {

    private static final RowMapper<EventDelivery> MAPPER = (rs, i) -> new EventDelivery(
            rs.getString("event_id"),
            rs.getInt("subscription_id"),
            rs.getString("status"),
            rs.getInt("attempts"),
            rs.getString("next_retry_at"),
            rs.getString("delivered_at"),
            (Integer) rs.getObject("last_status_code"),
            rs.getString("last_error"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public EventDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EventDelivery delivery) {
        jdbc.update("""
                INSERT INTO event_deliveries (event_id, subscription_id, status, attempts,
                                              next_retry_at, delivered_at, last_status_code,
                                              last_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                delivery.eventId(), delivery.subscriptionId(), delivery.status(), delivery.attempts(),
                delivery.nextRetryAt(), delivery.deliveredAt(), delivery.lastStatusCode(),
                delivery.lastError(), delivery.createdAt());
    }

    public Optional<EventDelivery> find(String eventId, int subscriptionId) {
        return jdbc.query(
                "SELECT * FROM event_deliveries WHERE event_id = ? AND subscription_id = ?",
                MAPPER, eventId, subscriptionId).stream().findFirst();
    }

    /** 状态推进（含 attempts / next_retry_at / last_*）；终态后不再变更由调用方保证。 */
    public void update(EventDelivery delivery) {
        jdbc.update("""
                UPDATE event_deliveries SET status = ?, attempts = ?, next_retry_at = ?,
                                            delivered_at = ?, last_status_code = ?, last_error = ?
                WHERE event_id = ? AND subscription_id = ?""",
                delivery.status(), delivery.attempts(), delivery.nextRetryAt(),
                delivery.deliveredAt(), delivery.lastStatusCode(), delivery.lastError(),
                delivery.eventId(), delivery.subscriptionId());
    }

    public List<EventDelivery> findByEventId(String eventId) {
        return jdbc.query("SELECT * FROM event_deliveries WHERE event_id = ? ORDER BY subscription_id ASC",
                MAPPER, eventId);
    }

    /** DeliverySweeper 入口：到期未投递的 PENDING 行（next_retry_at 为空的行由内存重试负责，不扫）。 */
    public List<EventDelivery> findDuePending(String nowIso) {
        return jdbc.query("""
                SELECT * FROM event_deliveries
                WHERE status = 'PENDING' AND next_retry_at IS NOT NULL AND next_retry_at <= ?
                ORDER BY id ASC""", MAPPER, nowIso);
    }

    /** 去重判定：窗口内已 DELIVERED 的投递对应事件 payload（由调用方解析比对 dedup_key）。 */
    public List<String> findDeliveredPayloadsSince(int subscriptionId, String sinceIso) {
        return jdbc.queryForList("""
                SELECT e.payload FROM event_deliveries d
                JOIN events e ON e.event_id = d.event_id
                WHERE d.subscription_id = ? AND d.status = 'DELIVERED' AND e.occurred_at >= ?""",
                String.class, subscriptionId, sinceIso);
    }
}
