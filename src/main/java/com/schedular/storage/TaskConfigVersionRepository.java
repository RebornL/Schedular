package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** task_config_versions 表唯一入口；UNIQUE(task_id, version)，只增不改。 */
@Repository
public class TaskConfigVersionRepository {

    private static final RowMapper<TaskConfigVersion> MAPPER = (rs, i) -> new TaskConfigVersion(
            rs.getLong("id"),
            rs.getString("task_id"),
            rs.getInt("version"),
            rs.getString("definition"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public TaskConfigVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskConfigVersion version) {
        jdbc.update(
                "INSERT INTO task_config_versions (task_id, version, definition, created_at) VALUES (?, ?, ?, ?)",
                version.taskId(), version.version(), version.definition(), version.createdAt());
    }

    public List<TaskConfigVersion> findByTaskId(String taskId) {
        return jdbc.query(
                "SELECT id, task_id, version, definition, created_at FROM task_config_versions WHERE task_id = ? ORDER BY version",
                MAPPER, taskId);
    }

    public Optional<TaskConfigVersion> findByTaskIdAndVersion(String taskId, int version) {
        return jdbc.query(
                "SELECT id, task_id, version, definition, created_at FROM task_config_versions WHERE task_id = ? AND version = ?",
                MAPPER, taskId, version).stream().findFirst();
    }
}
