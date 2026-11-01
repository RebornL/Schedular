-- V1：8 张表（与 sqlite/V1__init.sql DDL 语义一致；id 主键用 BIGINT AUTO_INCREMENT，
-- 业务主键/哈希列用 VARCHAR 以便建索引；时间 TEXT/VARCHAR 存 ISO-8601 带偏移；JSON 列 TEXT）。

CREATE TABLE tasks (
    task_id                    VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id                  VARCHAR(64) NOT NULL,
    owner_user_id              VARCHAR(64) NOT NULL,
    permission_context_ref     VARCHAR(255) NULL,
    name                       VARCHAR(255) NOT NULL,
    description                TEXT NULL,
    task_type                  VARCHAR(64) NOT NULL,
    agent_id                   VARCHAR(64) NOT NULL,
    agent_version              VARCHAR(64) NULL,
    trigger_type               VARCHAR(64) NOT NULL,
    schedule_type              VARCHAR(64) NOT NULL,
    cron_expression            VARCHAR(128) NOT NULL,
    timezone                   VARCHAR(64) NOT NULL,
    start_at                   VARCHAR(64) NULL,
    end_at                     VARCHAR(64) NULL,
    instruction                TEXT NOT NULL,
    parameters                 TEXT NULL,
    context_refs               TEXT NULL,
    timeout_seconds            INT NOT NULL,
    retry_count                INT NOT NULL,
    max_concurrent_executions  INT NOT NULL,
    current_version            INT NOT NULL DEFAULT 1,
    status                     VARCHAR(32) NOT NULL,
    consecutive_failures       INT NOT NULL DEFAULT 0,
    created_at                 VARCHAR(64) NOT NULL,
    updated_at                 VARCHAR(64) NOT NULL,
    INDEX idx_tasks_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_config_versions (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(64) NOT NULL,
    version     INT NOT NULL,
    definition  TEXT NULL,
    created_at  VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_task_versions (task_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE executions (
    execution_id         VARCHAR(64) NOT NULL,
    task_id              VARCHAR(64) NOT NULL,
    config_version       INT NOT NULL,
    trigger_type         VARCHAR(64) NOT NULL,
    scheduled_fire_time  VARCHAR(64) NULL,
    attempt              INT NOT NULL DEFAULT 1,
    status               VARCHAR(32) NOT NULL,
    result               TEXT NULL,
    summary              TEXT NULL,
    last_error           TEXT NULL,
    callback_token       VARCHAR(128) NULL,
    started_at           VARCHAR(64) NULL,
    finished_at          VARCHAR(64) NULL,
    created_at           VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_executions_id (execution_id),
    INDEX idx_executions_task_status (task_id, status),
    INDEX idx_executions_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE events (
    event_id     VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    task_id      VARCHAR(64) NULL,
    execution_id VARCHAR(64) NULL,
    severity     VARCHAR(32) NULL,
    occurred_at  VARCHAR(64) NOT NULL,
    payload      TEXT NULL,
    UNIQUE KEY uk_events_id (event_id),
    INDEX idx_events_tenant_occurred (tenant_id, occurred_at),
    INDEX idx_events_task (task_id),
    INDEX idx_events_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_deliveries (
    id                BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id          VARCHAR(64) NOT NULL,
    subscription_id   BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL,
    attempts          INT NOT NULL DEFAULT 0,
    next_retry_at     VARCHAR(64) NULL,
    delivered_at      VARCHAR(64) NULL,
    last_status_code  INT NULL,
    last_error        TEXT NULL,
    created_at        VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_deliveries_event_subscription (event_id, subscription_id),
    INDEX idx_deliveries_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subscriptions (
    id                     BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id              VARCHAR(64) NOT NULL,
    callback_url           VARCHAR(512) NOT NULL,
    secret                 VARCHAR(128) NOT NULL,
    filter_task_ids        TEXT NULL,
    filter_event_types     TEXT NULL,
    min_severity           VARCHAR(32) NULL,
    dedup_window_seconds   INT NULL,
    enabled                INT NOT NULL DEFAULT 1,
    created_at             VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agents (
    agent_id       VARCHAR(64) NOT NULL PRIMARY KEY,
    agent_version  VARCHAR(64) NULL,
    trigger_type   VARCHAR(64) NOT NULL,
    endpoint_url   VARCHAR(512) NOT NULL,
    result_mode    VARCHAR(32) NOT NULL,
    auth_headers   TEXT NULL,
    enabled        INT NOT NULL DEFAULT 1,
    created_at     VARCHAR(64) NOT NULL,
    updated_at     VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_keys (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    key_hash     VARCHAR(64) NOT NULL,
    caller_name  VARCHAR(128) NOT NULL,
    role         VARCHAR(32) NOT NULL,
    tenant_id    VARCHAR(64) NULL,
    enabled      INT NOT NULL DEFAULT 1,
    created_at   VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_api_keys_hash (key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
