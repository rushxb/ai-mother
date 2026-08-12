SET @tenant_credit_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user_credit_transaction'
      AND column_name = 'tenantId'
);
SET @tenant_credit_column_sql = IF(
    @tenant_credit_column_exists = 0,
    'ALTER TABLE user_credit_transaction
        ADD COLUMN tenantId bigint NULL COMMENT ''生成任务所属租户；非生成和历史流水为空'' AFTER userId',
    'SELECT 1'
);
PREPARE tenant_credit_column_statement FROM @tenant_credit_column_sql;
EXECUTE tenant_credit_column_statement;
DEALLOCATE PREPARE tenant_credit_column_statement;

SET @tenant_credit_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user_credit_transaction'
      AND index_name = 'idx_tenant_generation_budget'
);
SET @tenant_credit_index_sql = IF(
    @tenant_credit_index_exists = 0,
    'ALTER TABLE user_credit_transaction
        ADD INDEX idx_tenant_generation_budget
        (tenantId, type, isDelete, createTime, bizId)',
    'SELECT 1'
);
PREPARE tenant_credit_index_statement FROM @tenant_credit_index_sql;
EXECUTE tenant_credit_index_statement;
DEALLOCATE PREPARE tenant_credit_index_statement;
