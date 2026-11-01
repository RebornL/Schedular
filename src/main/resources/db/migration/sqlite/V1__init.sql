-- V1：8 张表（spec 7 张 + event_deliveries，见 docs/design/README.md 细化 2）
-- 时间一律 TEXT 存 ISO-8601 带偏移；JSON 一律 TEXT 存 JSON 字符串；无任何物理删除约定（无 DELETE）。

CREATE TABLE tasks (
    task_id                    TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL,
    owner_user_id              TEXT NOT NULL,
    permission_context_ref     TEXT,
    name                       TEXT NOT NULL,
    description                TEXT,
    task_type                  TEXT NOT NULL,
    agent_id                   TEXT NOT NULL,
    agent_version              TEXT,
    trigger_type               TEXT NOT NULL,
    schedule_type              TEXT NOT NULL,
    cron_expression            TEXT NOT NULL,
    timezone                   TEXT NOT NULL,
    start_at                   TEXT,
    end_at                     TEXT,
    instruction                TEXT NOT NULL,
    parameters                 TEXT,
    context_refs               TEXT,
    timeout_seconds            INTEGER NOT NULL,
    retry_count                INTEGER NOT NULL,
    max_concurrent_executions  INTEGER NOT NULL,
    current_version            INTEGER NOT NULL DEFAULT 1,
    status                     TEXT NOT NULL,
    consecutive_failures       INTEGER NOT NULL DEFAULT 0,
    created_at                 TEXT NOT NULL,
    updated_at                 TEXT NOT NULL
);
CREATE INDEX idx_tasks_tenant_status ON tasks (tenant_id, status);

CREATE TABLE task_config_versions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     TEXT NOT NULL,
    version     INTEGER NOT NULL,
    definition  TEXT,
    created_at  TEXT NOT NULL,
    UNIQUE (task_id, version)
);

CREATE TABLE executions (
    execution_id         TEXT NOT NULL UNIQUE,
    task_id              TEXT NOT NULL,
    config_version       INTEGER NOT NULL,
    trigger_type         TEXT NOT NULL,
    scheduled_fire_time  TEXT,
    attempt              INTEGER NOT NULL DEFAULT 1,
    status               TEXT NOT NULL,
    result               TEXT,
    summary              TEXT,
    last_error           TEXT,
    callback_token       TEXT,
    started_at           TEXT,
    finished_at          TEXT,
    created_at           TEXT NOT NULL
);
CREATE INDEX idx_executions_task_status ON executions (task_id, status);
CREATE INDEX idx_executions_status ON executions (status);

CREATE TABLE events (
    event_id     TEXT NOT NULL UNIQUE,
    event_type   TEXT NOT NULL,
    tenant_id    TEXT NOT NULL,
    task_id      TEXT,
    execution_id TEXT,
    severity     TEXT,
    occurred_at  TEXT NOT NULL,
    payload      TEXT
);
CREATE INDEX idx_events_tenant_occurred ON events (tenant_id, occurred_at);
CREATE INDEX idx_events_task ON events (task_id);
CREATE INDEX idx_events_type ON events (event_type);

CREATE TABLE event_deliveries (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id          TEXT NOT NULL,
    subscription_id   INTEGER NOT NULL,
    status            TEXT NOT NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_retry_at     TEXT,
    delivered_at      TEXT,
    last_status_code  INTEGER,
    last_error        TEXT,
    created_at        TEXT NOT NULL,
    UNIQUE (event_id, subscription_id)
);
CREATE INDEX idx_deliveries_status_retry ON event_deliveries (status, next_retry_at);

CREATE TABLE subscriptions (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id              TEXT NOT NULL,
    callback_url           TEXT NOT NULL,
    secret                 TEXT NOT NULL,
    filter_task_ids        TEXT,
    filter_event_types     TEXT,
    min_severity           TEXT,
    dedup_window_seconds   INTEGER,
    enabled                INTEGER NOT NULL DEFAULT 1,
    created_at             TEXT NOT NULL
);

CREATE TABLE agents (
    agent_id       TEXT PRIMARY KEY,
    agent_version  TEXT,
    trigger_type   TEXT NOT NULL,
    endpoint_url   TEXT NOT NULL,
    result_mode    TEXT NOT NULL,
    auth_headers   TEXT,
    enabled        INTEGER NOT NULL DEFAULT 1,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE TABLE api_keys (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash     TEXT NOT NULL UNIQUE,
    caller_name  TEXT NOT NULL,
    role         TEXT NOT NULL,
    tenant_id    TEXT,
    enabled      INTEGER NOT NULL DEFAULT 1,
    created_at   TEXT NOT NULL
);
