-- Production hardening for tenant-scoped Milvus memory and durable deletion.

SET @memory_index_next_attempt_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexNextAttemptAt'
);
SET @memory_index_next_attempt_sql = IF(
    @memory_index_next_attempt_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexNextAttemptAt datetime(6) null comment ''next semantic-memory indexing attempt'' AFTER memoryIndexError',
    'SELECT 1'
);
PREPARE memory_index_next_attempt_statement FROM @memory_index_next_attempt_sql;
EXECUTE memory_index_next_attempt_statement;
DEALLOCATE PREPARE memory_index_next_attempt_statement;

SET @memory_index_lease_owner_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexLeaseOwner'
);
SET @memory_index_lease_owner_sql = IF(
    @memory_index_lease_owner_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexLeaseOwner varchar(128) null comment ''semantic-memory outbox lease owner'' AFTER memoryIndexNextAttemptAt',
    'SELECT 1'
);
PREPARE memory_index_lease_owner_statement FROM @memory_index_lease_owner_sql;
EXECUTE memory_index_lease_owner_statement;
DEALLOCATE PREPARE memory_index_lease_owner_statement;

SET @memory_index_lease_until_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND column_name = 'memoryIndexLeaseUntil'
);
SET @memory_index_lease_until_sql = IF(
    @memory_index_lease_until_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memoryIndexLeaseUntil datetime(6) null comment ''semantic-memory outbox lease expiry'' AFTER memoryIndexLeaseOwner',
    'SELECT 1'
);
PREPARE memory_index_lease_until_statement FROM @memory_index_lease_until_sql;
EXECUTE memory_index_lease_until_statement;
DEALLOCATE PREPARE memory_index_lease_until_statement;

SET @memory_outbox_claim_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'generation_task'
      AND index_name = 'idx_memory_outbox_claim'
);
SET @memory_outbox_claim_index_sql = IF(
    @memory_outbox_claim_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_memory_outbox_claim (memoryIndexedAt, memoryIndexNextAttemptAt, memoryIndexLeaseUntil, memoryIndexAttempts, status, isDelete, endTime)',
    'SELECT 1'
);
PREPARE memory_outbox_claim_index_statement FROM @memory_outbox_claim_index_sql;
EXECUTE memory_outbox_claim_index_statement;
DEALLOCATE PREPARE memory_outbox_claim_index_statement;

CREATE TABLE IF NOT EXISTS semantic_memory_deletion_outbox
(
    id                bigint auto_increment primary key,
    operationId       char(64)                            not null,
    operationType     varchar(32)                         not null,
    tenantId          bigint                              not null,
    appId             bigint                              not null,
    requestedByUserId bigint                              not null,
    attempts          int         default 0               not null,
    nextAttemptAt     datetime(6)                         not null,
    leaseOwner        varchar(128)                        null,
    leaseUntil        datetime(6)                         null,
    lastError         varchar(1000)                       null,
    completedAt       datetime(6)                         null,
    createTime        datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime        datetime(6) default CURRENT_TIMESTAMP(6) not null
        on update CURRENT_TIMESTAMP(6),
    unique key uk_semantic_memory_deletion_operation (operationId),
    unique key uk_semantic_memory_deletion_scope (operationType, tenantId, appId),
    index idx_semantic_memory_deletion_claim
        (completedAt, nextAttemptAt, leaseUntil, id),
    constraint chk_semantic_memory_deletion_type
        check (operationType = 'DELETE_APPLICATION'),
    constraint chk_semantic_memory_deletion_identity
        check (tenantId > 0 and appId > 0 and requestedByUserId > 0),
    constraint chk_semantic_memory_deletion_attempts check (attempts >= 0),
    constraint chk_semantic_memory_deletion_lease check (
        (leaseOwner is null and leaseUntil is null)
        or (leaseOwner is not null and leaseUntil is not null)
    )
) comment 'durable outbox for deletion of derived Milvus semantic memory'
  collate = utf8mb4_unicode_ci;
