-- Durable Idempotency-Key semantics for generation task submission.

SET @generation_task_idempotency_key_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'idempotencyKeyHash'
);
SET @generation_task_idempotency_key_sql = IF(
    @generation_task_idempotency_key_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN idempotencyKeyHash char(64) CHARACTER SET ascii COLLATE ascii_bin null comment ''Idempotency-Key SHA-256'' AFTER tenantId',
    'SELECT 1'
);
PREPARE generation_task_idempotency_key_statement FROM @generation_task_idempotency_key_sql;
EXECUTE generation_task_idempotency_key_statement;
DEALLOCATE PREPARE generation_task_idempotency_key_statement;

SET @generation_task_request_fingerprint_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'requestFingerprint'
);
SET @generation_task_request_fingerprint_sql = IF(
    @generation_task_request_fingerprint_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN requestFingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin null comment ''submission request SHA-256'' AFTER idempotencyKeyHash',
    'SELECT 1'
);
PREPARE generation_task_request_fingerprint_statement FROM @generation_task_request_fingerprint_sql;
EXECUTE generation_task_request_fingerprint_statement;
DEALLOCATE PREPARE generation_task_request_fingerprint_statement;

SET @generation_task_idempotency_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'uk_generation_task_submission_idempotency'
);
SET @generation_task_idempotency_index_sql = IF(
    @generation_task_idempotency_index_exists = 0,
    'ALTER TABLE generation_task ADD UNIQUE KEY uk_generation_task_submission_idempotency (tenantId, userId, appId, idempotencyKeyHash)',
    'SELECT 1'
);
PREPARE generation_task_idempotency_index_statement FROM @generation_task_idempotency_index_sql;
EXECUTE generation_task_idempotency_index_statement;
DEALLOCATE PREPARE generation_task_idempotency_index_statement;

SET @generation_task_idempotency_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_idempotency_pair'
      AND constraint_type = 'CHECK'
);
SET @generation_task_idempotency_check_sql = IF(
    @generation_task_idempotency_check_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT chk_generation_task_idempotency_pair CHECK ((idempotencyKeyHash IS NULL AND requestFingerprint IS NULL) OR (idempotencyKeyHash IS NOT NULL AND requestFingerprint IS NOT NULL))',
    'SELECT 1'
);
PREPARE generation_task_idempotency_check_statement FROM @generation_task_idempotency_check_sql;
EXECUTE generation_task_idempotency_check_statement;
DEALLOCATE PREPARE generation_task_idempotency_check_statement;
