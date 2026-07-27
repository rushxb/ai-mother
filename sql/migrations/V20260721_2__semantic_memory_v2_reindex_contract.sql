-- Versioned relational marker that safely replays v1-indexed task memories into the v2 contract.

SET @memory_index_contract_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexContractVersion'
);
SET @memory_index_contract_version_sql = IF(
    @memory_index_contract_version_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexContractVersion int default 0 not null comment ''indexed semantic-memory contract version; 0 means pending''',
    'SELECT 1'
);
PREPARE memory_index_contract_version_statement FROM @memory_index_contract_version_sql;
EXECUTE memory_index_contract_version_statement;
DEALLOCATE PREPARE memory_index_contract_version_statement;

SET @memory_outbox_contract_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND index_name = 'idx_memory_outbox_contract_claim'
);
SET @memory_outbox_contract_index_sql = IF(
    @memory_outbox_contract_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_memory_outbox_contract_claim (memoryIndexContractVersion, memoryIndexedAt, memoryIndexNextAttemptAt, memoryIndexLeaseUntil, memoryIndexAttempts, status, isDelete, endTime)',
    'SELECT 1'
);
PREPARE memory_outbox_contract_index_statement FROM @memory_outbox_contract_index_sql;
EXECUTE memory_outbox_contract_index_statement;
DEALLOCATE PREPARE memory_outbox_contract_index_statement;

SET @memory_index_contract_check_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_memory_contract_version'
      AND constraint_type = 'CHECK'
);
SET @memory_index_contract_check_sql = IF(
    @memory_index_contract_check_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT chk_generation_task_memory_contract_version CHECK (memoryIndexContractVersion >= 0)',
    'SELECT 1'
);
PREPARE memory_index_contract_check_statement FROM @memory_index_contract_check_sql;
EXECUTE memory_index_contract_check_statement;
DEALLOCATE PREPARE memory_index_contract_check_statement;
