package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** agents 表唯一入口；无物理删除，停用 = enabled=0。 */
@Repository
public class AgentRepository {

    private static final RowMapper<Agent> MAPPER = (rs, i) -> new Agent(
            rs.getString("agent_id"),
            rs.getString("agent_version"),
            rs.getString("trigger_type"),
            rs.getString("endpoint_url"),
            rs.getString("result_mode"),
            rs.getString("auth_headers"),
            rs.getInt("enabled") == 1,
            rs.getString("created_at"),
            rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsById(String agentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE agent_id = ?", Integer.class, agentId);
        return count != null && count > 0;
    }

    public void insert(Agent agent) {
        jdbc.update("""
                INSERT INTO agents (agent_id, agent_version, trigger_type, endpoint_url,
                                    result_mode, auth_headers, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                agent.agentId(), agent.agentVersion(), agent.triggerType(), agent.endpointUrl(),
                agent.resultMode(), agent.authHeaders(), agent.enabled() ? 1 : 0,
                agent.createdAt(), agent.updatedAt());
    }

    public Optional<Agent> findById(String agentId) {
        return jdbc.query(
                "SELECT agent_id, agent_version, trigger_type, endpoint_url, result_mode, auth_headers, enabled, created_at, updated_at FROM agents WHERE agent_id = ?",
                MAPPER, agentId).stream().findFirst();
    }

    public List<Agent> findAll() {
        return jdbc.query(
                "SELECT agent_id, agent_version, trigger_type, endpoint_url, result_mode, auth_headers, enabled, created_at, updated_at FROM agents ORDER BY agent_id",
                MAPPER);
    }

    public void update(Agent agent) {
        jdbc.update("""
                UPDATE agents SET agent_version = ?, trigger_type = ?, endpoint_url = ?,
                                  result_mode = ?, auth_headers = ?, updated_at = ?
                WHERE agent_id = ?""",
                agent.agentVersion(), agent.triggerType(), agent.endpointUrl(),
                agent.resultMode(), agent.authHeaders(), agent.updatedAt(), agent.agentId());
    }

    public int disable(String agentId, String updatedAt) {
        return jdbc.update(
                "UPDATE agents SET enabled = 0, updated_at = ? WHERE agent_id = ?",
                updatedAt, agentId);
    }
}
