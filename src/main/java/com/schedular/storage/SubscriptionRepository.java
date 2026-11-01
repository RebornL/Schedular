package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** subscriptions 表唯一入口；无物理 DELETE（停用 = enabled=0）。 */
@Repository
public class SubscriptionRepository {

    private static final RowMapper<Subscription> MAPPER = (rs, i) -> new Subscription(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("callback_url"),
            rs.getString("secret"),
            rs.getString("filter_task_ids"),
            rs.getString("filter_event_types"),
            rs.getString("min_severity"),
            (Integer) rs.getObject("dedup_window_seconds"),
            rs.getInt("enabled") == 1,
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Subscription subscription) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO subscriptions (tenant_id, callback_url, secret, filter_task_ids,
                                               filter_event_types, min_severity, dedup_window_seconds,
                                               enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    new String[]{"id"});
            ps.setString(1, subscription.tenantId());
            ps.setString(2, subscription.callbackUrl());
            ps.setString(3, subscription.secret());
            ps.setString(4, subscription.filterTaskIds());
            ps.setString(5, subscription.filterEventTypes());
            ps.setString(6, subscription.minSeverity());
            if (subscription.dedupWindowSeconds() != null) {
                ps.setInt(7, subscription.dedupWindowSeconds());
            } else {
                ps.setNull(7, java.sql.Types.INTEGER);
            }
            ps.setInt(8, subscription.enabled() ? 1 : 0);
            ps.setString(9, subscription.createdAt());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Subscription> findById(long id) {
        return jdbc.query("SELECT * FROM subscriptions WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Subscription> findByTenant(String tenantId) {
        return jdbc.query("SELECT * FROM subscriptions WHERE tenant_id = ? ORDER BY id ASC", MAPPER, tenantId);
    }

    public void disable(long id) {
        jdbc.update("UPDATE subscriptions SET enabled = 0 WHERE id = ?", id);
    }
}
