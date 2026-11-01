package com.schedular.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** executions 表唯一入口；终态不可回退，链路故障只更新 last_error 并回到 PENDING。 */
@Repository
public class ExecutionRepository {

    private static final RowMapper<Execution> MAPPER = (rs, i) -> new Execution(
            rs.getString("execution_id"),
            rs.getString("task_id"),
            rs.getInt("config_version"),
            rs.getString("trigger_type"),
            rs.getString("scheduled_fire_time"),
            rs.getInt("attempt"),
            rs.getString("status"),
            rs.getString("result"),
            rs.getString("summary"),
            rs.getString("last_error"),
            rs.getString("callback_token"),
            rs.getString("started_at"),
            rs.getString("finished_at"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public ExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Execution execution) {
        jdbc.update("""
                INSERT INTO executions (execution_id, task_id, config_version, trigger_type,
                                        scheduled_fire_time, attempt, status, result, summary,
                                        last_error, callback_token, started_at, finished_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                execution.executionId(), execution.taskId(), execution.configVersion(),
                execution.triggerType(), execution.scheduledFireTime(), execution.attempt(),
                execution.status(), execution.result(), execution.summary(), execution.lastError(),
                execution.callbackToken(), execution.startedAt(), execution.finishedAt(),
                execution.createdAt());
    }

    public Optional<Execution> findById(String executionId) {
        return jdbc.query("SELECT * FROM executions WHERE execution_id = ?",
                MAPPER, executionId).stream().findFirst();
    }

    public List<Execution> findByTaskId(String taskId) {
        return jdbc.query(
                "SELECT * FROM executions WHERE task_id = ? ORDER BY created_at DESC",
                MAPPER, taskId);
    }

    public void markRunning(String executionId, String startedAt) {
        jdbc.update("UPDATE executions SET status = ?, started_at = ? WHERE execution_id = ?",
                Execution.STATUS_RUNNING, startedAt, executionId);
    }

    /** 终态落库：result JSON、summary、finished_at 一起写入。 */
    public void finalizeTerminal(String executionId, String status, String resultJson,
                                 String summary, String lastError, String finishedAt) {
        jdbc.update("""
                UPDATE executions SET status = ?, result = ?, summary = ?,
                                      last_error = COALESCE(?, last_error), finished_at = ?
                WHERE execution_id = ?""",
                status, resultJson, summary, lastError, finishedAt, executionId);
    }

    /** 链路故障：状态回到 PENDING（保持可重试语义），记录 last_error。 */
    public void recordLinkFailure(String executionId, String lastError) {
        jdbc.update("UPDATE executions SET status = ?, last_error = ? WHERE execution_id = ?",
                Execution.STATUS_PENDING, lastError, executionId);
    }

    /** 并发判定用：task 的 PENDING+RUNNING 行，最早在前；excludeExecutionId 为 null 时不过滤。 */
    public List<Execution> findActiveByTask(String taskId, String excludeExecutionId) {
        return jdbc.query("""
                SELECT * FROM executions
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                  AND (? IS NULL OR execution_id <> ?)
                ORDER BY created_at ASC""",
                MAPPER, taskId, excludeExecutionId, excludeExecutionId);
    }

    /** 链路重试：attempt 单调递增。 */
    public void updateAttempt(String executionId, int attempt) {
        jdbc.update("UPDATE executions SET attempt = ? WHERE execution_id = ?",
                attempt, executionId);
    }

    /** 重试穷尽等链路终局：FAILED 终态 + last_error；已终态不回退。 */
    public void finalizeFailed(String executionId, String lastError, String finishedAt) {
        jdbc.update("""
                UPDATE executions SET status = ?, last_error = ?, finished_at = ?
                WHERE execution_id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'TIMEOUT')""",
                Execution.STATUS_FAILED, lastError, finishedAt, executionId);
    }

    /**
     * 超时兜底扫描（跨方言，禁用 SQLite 专属 julianday）：SQL 只按 status='RUNNING' 与
     * started_at 粗过滤（cutoff = now-1s，UTC 字符串比较），timeout_seconds 的精确过期判定在
     * Java 侧由 sweeper 结合任务定义完成——RUNNING 行数量级小，v1 可接受。
     */
    public List<Execution> findTimeoutRunning(String cutoff) {
        return jdbc.query("""
                SELECT * FROM executions
                WHERE status = ? AND started_at IS NOT NULL AND started_at <= ?""",
                MAPPER, Execution.STATUS_RUNNING, cutoff);
    }

    /** 超时终局原子落库：仅 RUNNING 行生效（与回调竞态只有一方成功），返回影响行数。 */
    public int finalizeTimeoutIfRunning(String executionId, String lastError, String finishedAt) {
        return jdbc.update("""
                UPDATE executions SET status = ?, last_error = ?, finished_at = ?
                WHERE execution_id = ? AND status = ?""",
                Execution.STATUS_TIMEOUT, lastError, finishedAt, executionId,
                Execution.STATUS_RUNNING);
    }

    public void updateCallbackToken(String executionId, String callbackToken) {
        jdbc.update("UPDATE executions SET callback_token = ? WHERE execution_id = ?",
                callbackToken, executionId);
    }
}
