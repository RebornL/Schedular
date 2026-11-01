-- V2：本地开发种子 key（与 sqlite/V2 相同内容；仅本地使用，生产部署必须清理并发放正式 key）。
INSERT INTO api_keys (key_hash, caller_name, role, tenant_id, enabled, created_at) VALUES
    ('9dd7feaa9d6313c2387d046bd5672c683c80bc8f0ed39fbe2b862e1654cac2bd', 'dev-admin',  'ADMIN',  NULL,         1, '2026-01-01T00:00:00+08:00'),
    ('a21ce249648151e15983b69ef4160f2de2dae59f152c214c0863ba277c0a1670', 'dev-caller', 'CALLER', 'tenant-dev', 1, '2026-01-01T00:00:00+08:00');
