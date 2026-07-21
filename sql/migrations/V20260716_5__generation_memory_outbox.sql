-- Durable relational outbox for rebuilding the Milvus semantic-memory derived index.
SET @memory_indexed_at_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexedAt'
);
SET @memory_indexed_at_sql = IF(
    @memory_indexed_at_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexedAt datetime(6) null comment ''Milvus memory indexed time'' AFTER memorySummary',
    'SELECT 1'
);
PREPARE memory_indexed_at_statement FROM @memory_indexed_at_sql;
EXECUTE memory_indexed_at_statement;
DEALLOCATE PREPARE memory_indexed_at_statement;

SET @memory_index_attempts_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexAttempts'
);
SET @memory_index_attempts_sql = IF(
    @memory_index_attempts_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexAttempts int default 0 not null comment ''Milvus memory index attempts'' AFTER memoryIndexedAt',
    'SELECT 1'
);
PREPARE memory_index_attempts_statement FROM @memory_index_attempts_sql;
EXECUTE memory_index_attempts_statement;
DEALLOCATE PREPARE memory_index_attempts_statement;

SET @memory_index_error_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexError'
);
SET @memory_index_error_sql = IF(
    @memory_index_error_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexError varchar(1000) null comment ''Milvus memory latest index error'' AFTER memoryIndexAttempts',
    'SELECT 1'
);
PREPARE memory_index_error_statement FROM @memory_index_error_sql;
EXECUTE memory_index_error_statement;
DEALLOCATE PREPARE memory_index_error_statement;

SET @memory_outbox_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND index_name = 'idx_memory_outbox'
);
SET @memory_outbox_index_sql = IF(
    @memory_outbox_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_memory_outbox (memoryIndexedAt, memoryIndexAttempts, status, isDelete, endTime)',
    'SELECT 1'
);
PREPARE memory_outbox_index_statement FROM @memory_outbox_index_sql;
EXECUTE memory_outbox_index_statement;
DEALLOCATE PREPARE memory_outbox_index_statement;
