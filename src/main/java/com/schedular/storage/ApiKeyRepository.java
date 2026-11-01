package com.schedular.storage;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** api_keys 表唯一入口；本票仅 security 需要，其余 repository 随后票落地。 */
@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return jdbc.query(
                "SELECT id, key_hash, caller_name, role, tenant_id, enabled FROM api_keys WHERE key_hash = ?",
                (rs, i) -> new ApiKey(
                        rs.getInt("id"),
                        rs.getString("key_hash"),
                        rs.getString("caller_name"),
                        rs.getString("role"),
                        rs.getString("tenant_id"),
                        rs.getInt("enabled") == 1),
                keyHash)
                .stream()
                .findFirst();
    }
}
