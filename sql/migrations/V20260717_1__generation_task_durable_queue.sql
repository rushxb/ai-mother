-- Durable generation command and Redis Streams dispatch metadata.
-- MySQL is the source of truth; Redis delivery can be reconstructed by polling queued rows.

SET @runtime_schema_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'runtimeSchemaVersion'
);
SET @runtime_schema_version_sql = IF(
    @runtime_schema_version_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN runtimeSchemaVersion int null comment ''可重建执行命令 schema 版本'' AFTER route',
    'SELECT 1'
);
PREPARE runtime_schema_version_statement FROM @runtime_schema_version_sql;
EXECUTE runtime_schema_version_statement;
DEALLOCATE PREPARE runtime_schema_version_statement;

SET @runtime_payload_json_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'runtimePayloadJson'
);
SET @runtime_payload_json_sql = IF(
    @runtime_payload_json_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN runtimePayloadJson mediumtext null comment ''跨实例可重建执行命令 JSON'' AFTER runtimeSchemaVersion',
    'SELECT 1'
);
PREPARE runtime_payload_json_statement FROM @runtime_payload_json_sql;
EXECUTE runtime_payload_json_statement;
DEALLOCATE PREPARE runtime_payload_json_statement;

SET @dispatch_at_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'dispatchAt'
);
SET @dispatch_at_sql = IF(
    @dispatch_at_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN dispatchAt datetime(6) null comment ''最近一次进入 durable queue 的时间'' AFTER runtimePayloadJson',
    'SELECT 1'
);
PREPARE dispatch_at_statement FROM @dispatch_at_sql;
EXECUTE dispatch_at_statement;
DEALLOCATE PREPARE dispatch_at_statement;

SET @dispatch_attempt_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'dispatchAttempt'
);
SET @dispatch_attempt_sql = IF(
    @dispatch_attempt_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN dispatchAttempt int default 0 not null comment ''durable queue 投递尝试次数'' AFTER dispatchAt',
    'SELECT 1'
);
PREPARE dispatch_attempt_statement FROM @dispatch_attempt_sql;
EXECUTE dispatch_attempt_statement;
DEALLOCATE PREPARE dispatch_attempt_statement;

SET @dispatch_error_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'dispatchError'
);
SET @dispatch_error_sql = IF(
    @dispatch_error_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN dispatchError varchar(1000) null comment ''最近一次 durable queue 投递错误'' AFTER dispatchAttempt',
    'SELECT 1'
);
PREPARE dispatch_error_statement FROM @dispatch_error_sql;
EXECUTE dispatch_error_statement;
DEALLOCATE PREPARE dispatch_error_statement;

SET @generation_dispatch_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_runtime_dispatch'
);
SET @generation_dispatch_index_sql = IF(
    @generation_dispatch_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_runtime_dispatch (status, dispatchAt, leaseOwner, isDelete, submittedAt)',
    'SELECT 1'
);
PREPARE generation_dispatch_index_statement FROM @generation_dispatch_index_sql;
EXECUTE generation_dispatch_index_statement;
DEALLOCATE PREPARE generation_dispatch_index_statement;
