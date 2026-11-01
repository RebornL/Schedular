package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** tasks 表唯一入口；无物理删除，停用 = status=STOPPED。 */
@Repository
public class TaskRepository {

    private static final RowMapper<Task> MAPPER = (rs, i) -> new Task(
            rs.getString("task_id"),
            rs.getString("tenant_id"),
            rs.getString("owner_user_id"),
            rs.getString("permission_context_ref"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("task_type"),
            rs.getString("agent_id"),
            rs.getString("agent_version"),
            rs.getString("trigger_type"),
            rs.getString("schedule_type"),
            rs.getString("cron_expression"),
            rs.getString("timezone"),
            rs.getString("start_at"),
            rs.getString("end_at"),
            rs.getString("instruction"),
            rs.getString("parameters"),
            rs.getString("context_refs"),
            rs.getInt("timeout_seconds"),
            rs.getInt("retry_count"),
            rs.getInt("max_concurrent_executions"),
            rs.getInt("current_version"),
            rs.getString("status"),
            rs.getInt("consecutive_failures"),
            rs.getString("created_at"),
            rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Task task) {
        jdbc.update("""
                INSERT INTO tasks (task_id, tenant_id, owner_user_id, permission_context_ref,
                                   name, description, task_type, agent_id, agent_version,
                                   trigger_type, schedule_type, cron_expression, timezone,
                                   start_at, end_at, instruction, parameters, context_refs,
                                   timeout_seconds, retry_count, max_concurrent_executions,
                                   current_version, status, consecutive_failures, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                task.taskId(), task.tenantId(), task.ownerUserId(), task.permissionContextRef(),
                task.name(), task.description(), task.taskType(), task.agentId(), task.agentVersion(),
                task.triggerType(), task.scheduleType(), task.cronExpression(), task.timezone(),
                task.startAt(), task.endAt(), task.instruction(), task.parameters(), task.contextRefs(),
                task.timeoutSeconds(), task.retryCount(), task.maxConcurrentExecutions(),
                task.currentVersion(), task.status(), task.consecutiveFailures(),
                task.createdAt(), task.updatedAt());
    }

    public Optional<Task> findById(String taskId) {
        return jdbc.query(
                "SELECT * FROM tasks WHERE task_id = ?", MAPPER, taskId).stream().findFirst();
    }

    public List<Task> findByTenant(String tenantId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM tasks WHERE tenant_id = ? ORDER BY created_at DESC", MAPPER, tenantId);
        }
        return jdbc.query(
                "SELECT * FROM tasks WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC",
                MAPPER, tenantId, status);
    }

    public int updateStatus(String taskId, String status, String updatedAt) {
        return jdbc.update(
                "UPDATE tasks SET status = ?, updated_at = ? WHERE task_id = ?",
                status, updatedAt, taskId);
    }

    /** 连续失败计数推进（清零或递增），单独列更新避免覆盖并发配置修改。 */
    public int updateConsecutiveFailures(String taskId, int count, String updatedAt) {
        return jdbc.update(
                "UPDATE tasks SET consecutive_failures = ?, updated_at = ? WHERE task_id = ?",
                count, updatedAt, taskId);
    }

    /** 全量更新配置列（不含 status/consecutive_failures/created_at），current_version 由调用方计算。 */
    public int update(Task task) {
        return jdbc.update("""
                UPDATE tasks SET owner_user_id = ?, permission_context_ref = ?, name = ?,
                                  description = ?, task_type = ?, agent_id = ?, agent_version = ?,
                                  trigger_type = ?, schedule_type = ?, cron_expression = ?,
                                  timezone = ?, start_at = ?, end_at = ?, instruction = ?,
                                  parameters = ?, context_refs = ?, timeout_seconds = ?,
                                  retry_count = ?, max_concurrent_executions = ?,
                                  current_version = ?, updated_at = ?
                WHERE task_id = ?""",
                task.ownerUserId(), task.permissionContextRef(), task.name(), task.description(),
                task.taskType(), task.agentId(), task.agentVersion(), task.triggerType(),
                task.scheduleType(), task.cronExpression(), task.timezone(), task.startAt(),
                task.endAt(), task.instruction(), task.parameters(), task.contextRefs(),
                task.timeoutSeconds(), task.retryCount(), task.maxConcurrentExecutions(),
                task.currentVersion(), task.updatedAt(), task.taskId());
    }
}
