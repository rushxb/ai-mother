-- Propagate the tenant boundary into durable AI runtime state.
SET @generation_task_tenant_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'tenantId'
);
SET @generation_task_tenant_column_sql = IF(
    @generation_task_tenant_column_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN tenantId bigint NULL AFTER userId',
    'SELECT 1'
);
PREPARE generation_task_tenant_column_statement FROM @generation_task_tenant_column_sql;
EXECUTE generation_task_tenant_column_statement;
DEALLOCATE PREPARE generation_task_tenant_column_statement;

UPDATE generation_task task
JOIN app application ON application.id = task.appId
SET task.tenantId = application.tenantId
WHERE task.tenantId IS NULL;

-- Fail the migration instead of leaving runtime rows outside a tenant boundary.
SET @generation_task_tenant_nullable = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'tenantId'
      AND is_nullable = 'YES'
);
SET @generation_task_tenant_not_null_sql = IF(
    @generation_task_tenant_nullable > 0,
    'ALTER TABLE generation_task MODIFY COLUMN tenantId bigint NOT NULL',
    'SELECT 1'
);
PREPARE generation_task_tenant_not_null_statement FROM @generation_task_tenant_not_null_sql;
EXECUTE generation_task_tenant_not_null_statement;
DEALLOCATE PREPARE generation_task_tenant_not_null_statement;

SET @generation_task_tenant_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_generation_task_tenant_runtime'
);
SET @generation_task_tenant_index_sql = IF(
    @generation_task_tenant_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_generation_task_tenant_runtime (tenantId, status, isDelete, submittedAt, id)',
    'SELECT 1'
);
PREPARE generation_task_tenant_index_statement FROM @generation_task_tenant_index_sql;
EXECUTE generation_task_tenant_index_statement;
DEALLOCATE PREPARE generation_task_tenant_index_statement;

SET @generation_task_tenant_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'fk_generation_task_tenant'
      AND constraint_type = 'FOREIGN KEY'
);
SET @generation_task_tenant_fk_sql = IF(
    @generation_task_tenant_fk_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT fk_generation_task_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id)',
    'SELECT 1'
);
PREPARE generation_task_tenant_fk_statement FROM @generation_task_tenant_fk_sql;
EXECUTE generation_task_tenant_fk_statement;
DEALLOCATE PREPARE generation_task_tenant_fk_statement;
